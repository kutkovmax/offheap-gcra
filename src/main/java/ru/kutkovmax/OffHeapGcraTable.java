package ru.kutkovmax;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class OffHeapGcraTable implements GcraTable {

    private static final ValueLayout.OfLong ELEMENT = ValueLayout.JAVA_LONG_UNALIGNED;

    private static final VarHandle LONG_AT;

    static {
        LONG_AT = MethodHandles.memorySegmentViewVarHandle(ELEMENT);
    }

    private final Arena arena;
    private final MemorySegment keys;
    private final MemorySegment cells;
    private final MemorySegment lastAccess;

    private final long capacity;
    private final int mask;

    private final long interval;
    private final long burstTolerance;
    private final long evictionTimeout;

    private final Thread cleaner;
    private volatile boolean running;

    OffHeapGcraTable(
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        GcraMath.validateCapacity(capacity);
        GcraMath.validateConfiguration(interval, burstTolerance, evictionTimeout);

        long bytes = (long) capacity * ELEMENT.byteSize();

        this.arena = Arena.ofShared();
        this.keys = arena.allocate(bytes);
        this.cells = arena.allocate(bytes);
        this.lastAccess = arena.allocate(bytes);

        this.capacity = capacity;
        this.mask = capacity - 1;

        this.interval = interval;
        this.burstTolerance = burstTolerance;
        this.evictionTimeout = evictionTimeout;

        this.running = true;
        this.cleaner = new Thread(this::cleanerLoop, "gcra-cleaner");
        this.cleaner.setDaemon(true);
        this.cleaner.start();
    }

    OffHeapGcraTable(int capacity, long interval, long burstTolerance) {
        this(capacity, interval, burstTolerance, 60_000);
    }

    @Override
    public int findOrClaim(long key, long now) {
        int start = indexFor(key);

        for (int i = 0; i < capacity; i++) {
            int index = (start + i) & mask;

            while (true) {
                long cell = getVolatile(cells, index);
                int state = GcraCell.state(cell);

                if (state == GcraCell.OCCUPIED) {
                    if (getPlain(keys, index) == key) {
                        return index;
                    }
                    break;
                }

                if (state == GcraCell.CLAIMING || state == GcraCell.EVICTING) {
                    Thread.onSpinWait();
                    continue;
                }

                if (state == GcraCell.EMPTY) {
                    long emptyCell = GcraCell.pack(GcraCell.EMPTY, 0);
                    if (cell != emptyCell) {
                        continue;
                    }
                    long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                    if (!compareAndSet(cells, index, emptyCell, claimingCell)) {
                        continue;
                    }

                    setRelease(keys, index, key);

                    long tat = now;
                    long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, tat);
                    setVolatile(cells, index, occupiedCell);

                    return index;
                }

                throw new IllegalStateException("Unexpected cell state: " + state);
            }
        }

        return -1;
    }

    @Override
    public boolean tryAcquire(long key) {
        long now = TimeProvider.nowNanos();
        int index = findOrClaim(key, now);
        if (index < 0) {
            return false;
        }
        return tryAcquireCell(index, now);
    }

    @Override
    public boolean tryAcquire(long key, long now) {
        int index = findOrClaim(key, now);
        if (index < 0) {
            return false;
        }
        return tryAcquireCell(index, now);
    }

    private boolean tryAcquireCell(int index, long now) {
        while (true) {
            long currentCell = getVolatile(cells, index);
            int state = GcraCell.state(currentCell);

            if (state != GcraCell.OCCUPIED) {
                Thread.onSpinWait();
                continue;
            }

            long currentTat = GcraCell.tat(currentCell);

            if (now < currentTat - burstTolerance) {
                return false;
            }

            long newTat = GcraMath.nextTat(currentTat, now, interval);
            long newCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);

            if (compareAndSet(cells, index, currentCell, newCell)) {
                setRelease(lastAccess, index, now);
                return true;
            }
        }
    }

    private int indexFor(long key) {
        long x = key;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdl;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53l;
        x ^= x >>> 33;
        return ((int) x) & mask;
    }

    private void cleanerLoop() {
        while (running) {
            try {
                Thread.sleep(evictionTimeout / 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            clean(TimeProvider.nowNanos());
        }
    }

    @Override
    public void clean(long now) {
        for (int index = 0; index < capacity; index++) {
            cleanCell(index, now);
        }
    }

    private void cleanCell(int index, long now) {
        long last = getAcquire(lastAccess, index);
        if (now - last < evictionTimeout) {
            return;
        }

        long currentCell = getVolatile(cells, index);
        if (GcraCell.state(currentCell) != GcraCell.OCCUPIED) {
            return;
        }

        long currentTat = GcraCell.tat(currentCell);
        long evictingCell = GcraCell.pack(GcraCell.EVICTING, currentTat);

        if (!compareAndSet(cells, index, currentCell, evictingCell)) {
            return;
        }

        long currentLast = getAcquire(lastAccess, index);
        if (currentLast != last) {
            compareAndSet(cells, index, evictingCell, currentCell);
            return;
        }

        setRelease(keys, index, 0);
        setRelease(lastAccess, index, 0);
        setVolatile(cells, index, GcraCell.pack(GcraCell.EMPTY, 0));
    }

    @Override
    public void close() {
        running = false;
        cleaner.interrupt();
        try {
            cleaner.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        arena.close();
    }

    private static long offset(int index) {
        return (long) index * ELEMENT.byteSize();
    }

    private static long getPlain(MemorySegment segment, int index) {
        return (long) LONG_AT.get(segment, offset(index));
    }

    private static long getVolatile(MemorySegment segment, int index) {
        return (long) LONG_AT.getVolatile(segment, offset(index));
    }

    private static long getAcquire(MemorySegment segment, int index) {
        return (long) LONG_AT.getAcquire(segment, offset(index));
    }

    private static void setRelease(MemorySegment segment, int index, long value) {
        LONG_AT.setRelease(segment, offset(index), value);
    }

    private static void setVolatile(MemorySegment segment, int index, long value) {
        LONG_AT.setVolatile(segment, offset(index), value);
    }

    private static boolean compareAndSet(MemorySegment segment, int index, long expected, long value) {
        return (boolean) LONG_AT.compareAndSet(segment, offset(index), expected, value);
    }
}
