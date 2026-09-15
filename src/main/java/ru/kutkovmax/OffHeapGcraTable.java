package ru.kutkovmax;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class OffHeapGcraTable implements GcraTable {

    private static final ValueLayout.OfLong ELEMENT = ValueLayout.JAVA_LONG_UNALIGNED;

    private static final VarHandle LONG_AT;

    static {
        LONG_AT = ValueLayout.JAVA_LONG.varHandle();
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
        this(capacity, interval, burstTolerance, 60_000_000_000L); // 60 секунд в наносекундах
    }

    @Override
    public int findOrClaim(long key, long now) {
        int start = indexFor(key);

        retry:
        while (true) {
            int firstTombstone = -1;

            for (int i = 0; i < capacity; i++) {
                int index = (start + i) & mask;
                long cell = getVolatile(cells, index);
                int state = GcraCell.state(cell);

                if (state == GcraCell.OCCUPIED) {
                    if (getAcquire(keys, index) == key) {
                        return index;
                    }
                    continue;
                }

                if (state == GcraCell.TOMBSTONE) {
                    if (firstTombstone < 0) {
                        firstTombstone = index;
                    }
                    continue;
                }

                if (state == GcraCell.CLAIMING || state == GcraCell.EVICTING) {
                    Thread.onSpinWait();
                    i--;
                    continue;
                }

                if (state == GcraCell.EMPTY) {
                    int target = (firstTombstone >= 0) ? firstTombstone : index;
                    long targetExpected = (firstTombstone >= 0)
                            ? GcraCell.pack(GcraCell.TOMBSTONE, 0)
                            : GcraCell.pack(GcraCell.EMPTY, 0);
                    long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                    if (!compareAndSet(cells, target, targetExpected, claimingCell)) {
                        continue retry;
                    }

                    setRelease(keys, target, key);
                    setRelease(lastAccess, target, now);
                    long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, now);
                    setVolatile(cells, target, occupiedCell);
                    return target;
                }
            }

            if (firstTombstone >= 0) {
                int target = firstTombstone;
                long tombstoneCell = GcraCell.pack(GcraCell.TOMBSTONE, 0);
                long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                if (!compareAndSet(cells, target, tombstoneCell, claimingCell)) {
                    continue retry;
                }

                setRelease(keys, target, key);
                setRelease(lastAccess, target, now);
                long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, now);
                setVolatile(cells, target, occupiedCell);
                return target;
            }

            return -1;
        }
    }

    @Override
    public boolean tryAcquire(long key) {
        return tryAcquire(key, TimeProvider.nowNanos());
    }

    @Override
    public boolean tryAcquire(long key, long now) {
        int start = indexFor(key);

        retry:
        while (true) {
            int firstTombstone = -1;

            for (int i = 0; i < capacity; i++) {
                int index = (start + i) & mask;
                long cell = getVolatile(cells, index);
                int state = GcraCell.state(cell);

                if (state == GcraCell.OCCUPIED) {
                    if (getAcquire(keys, index) == key) {
                        long currentTat = GcraCell.tat(cell);
                        if (now < currentTat - burstTolerance) {
                            return false;
                        }
                        long newTat = GcraMath.nextTat(currentTat, now, interval);
                        long newCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);
                        if (compareAndSet(cells, index, cell, newCell)) {
                            setRelease(lastAccess, index, now);
                            return true;
                        }
                        continue retry;
                    }
                    continue;
                }

                if (state == GcraCell.TOMBSTONE) {
                    if (firstTombstone < 0) {
                        firstTombstone = index;
                    }
                    continue;
                }

                if (state == GcraCell.CLAIMING || state == GcraCell.EVICTING) {
                    Thread.onSpinWait();
                    i--;
                    continue;
                }

                if (state == GcraCell.EMPTY) {
                    int target = (firstTombstone >= 0) ? firstTombstone : index;
                    long targetExpected = (firstTombstone >= 0)
                            ? GcraCell.pack(GcraCell.TOMBSTONE, 0)
                            : GcraCell.pack(GcraCell.EMPTY, 0);
                    long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                    if (!compareAndSet(cells, target, targetExpected, claimingCell)) {
                        continue retry;
                    }

                    setRelease(keys, target, key);
                    setRelease(lastAccess, target, now);
                    long newTat = GcraMath.newKeyTat(now, interval);
                    long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);
                    setVolatile(cells, target, occupiedCell);
                    return true;
                }
            }

            if (firstTombstone >= 0) {
                int target = firstTombstone;
                long tombstoneCell = GcraCell.pack(GcraCell.TOMBSTONE, 0);
                long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                if (!compareAndSet(cells, target, tombstoneCell, claimingCell)) {
                    continue retry;
                }

                setRelease(keys, target, key);
                setRelease(lastAccess, target, now);
                long newTat = GcraMath.newKeyTat(now, interval);
                long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);
                setVolatile(cells, target, occupiedCell);
                return true;
            }

            return false;
        }
    }

    boolean tryAcquireCell(int index, long expectedKey, long now) {
        long currentCell = getVolatile(cells, index);
        int state = GcraCell.state(currentCell);
        if (state != GcraCell.OCCUPIED) {
            return false;
        }
        if (getAcquire(keys, index) != expectedKey) {
            return false;
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
        return false;
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
        long sleepNanos = Math.max(10_000_000L, evictionTimeout / 2);
        long sleepMillis = sleepNanos / 1_000_000L;
        int sleepNanosPart = (int) (sleepNanos % 1_000_000L);

        while (running) {
            try {
                Thread.sleep(sleepMillis, sleepNanosPart);
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
        long currentCell = getVolatile(cells, index);
        if (GcraCell.state(currentCell) != GcraCell.OCCUPIED) {
            return;
        }

        long last = getAcquire(lastAccess, index);
        if (now <= last || now - last < evictionTimeout) {
            return;
        }

        long evictingCell = GcraCell.pack(GcraCell.EVICTING, 0);
        if (compareAndSet(cells, index, currentCell, evictingCell)) {
            setRelease(keys, index, 0L);
            setRelease(lastAccess, index, 0L);
            long tombstoneCell = GcraCell.pack(GcraCell.TOMBSTONE, 0);
            setVolatile(cells, index, tombstoneCell);
        }
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
