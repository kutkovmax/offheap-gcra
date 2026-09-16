package ru.kutkovmax;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;

final class HeapGcraTable implements GcraTable {

    private static final VarHandle KEYS_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle CELL_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle LAST_ACCESS_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private static final int OPEN = 0;
    private static final int CLOSING = 1;
    private static final int CLOSED = 2;

    private final AtomicInteger lifecycle = new AtomicInteger(OPEN);

    private final long[] keys;
    private final long[] cells;
    private final long[] lastAccess;

    private final int mask;

    private final long interval;
    private final long burstTolerance;
    private final long evictionTimeout;

    private final Thread cleaner;
    private volatile boolean running;

    private void checkOpen() {
        if (lifecycle.get() != OPEN) {
            throw new IllegalStateException("Limiter is closed");
        }
    }

    HeapGcraTable(
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        GcraMath.validateCapacity(capacity);
        GcraMath.validateConfiguration(interval, burstTolerance, evictionTimeout);

        this.keys = new long[capacity];
        this.cells = new long[capacity];
        this.lastAccess = new long[capacity];
        this.mask = capacity - 1;

        this.interval = interval;
        this.burstTolerance = burstTolerance;
        this.evictionTimeout = evictionTimeout;

        this.running = true;
        this.cleaner = new Thread(this::cleanerLoop, "gcra-cleaner");
        this.cleaner.setDaemon(true);
        this.cleaner.start();
    }

    HeapGcraTable(int capacity, long interval, long burstTolerance) {
        // ИСПРАВЛЕНИЕ: 60 секунд в наносекундах (60_000_000_000L), а не 60_000 (микросекунды)
        this(capacity, interval, burstTolerance, 60_000_000_000L);
    }

    @Override
    public int findOrClaim(long key, long now) {
        checkOpen();
        int start = indexFor(key);

        retry:
        while (true) {
            int firstTombstone = -1;

            for (int i = 0; i < cells.length; i++) {
                int index = (start + i) & mask;
                long cell = (long) CELL_HANDLE.getVolatile(cells, index);
                int state = GcraCell.state(cell);

                if (state == GcraCell.OCCUPIED) {
                    if ((long) KEYS_HANDLE.getAcquire(keys, index) == key) {
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

                    if (!CELL_HANDLE.compareAndSet(cells, target, targetExpected, claimingCell)) {
                        continue retry;
                    }

                    KEYS_HANDLE.setRelease(keys, target, key);
                    LAST_ACCESS_HANDLE.setRelease(lastAccess, target, now);
                    long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, now);
                    CELL_HANDLE.setVolatile(cells, target, occupiedCell);
                    return target;
                }
            }

            if (firstTombstone >= 0) {
                int target = firstTombstone;
                long tombstoneCell = GcraCell.pack(GcraCell.TOMBSTONE, 0);
                long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                if (!CELL_HANDLE.compareAndSet(cells, target, tombstoneCell, claimingCell)) {
                    continue retry;
                }

                KEYS_HANDLE.setRelease(keys, target, key);
                LAST_ACCESS_HANDLE.setRelease(lastAccess, target, now);
                long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, now);
                CELL_HANDLE.setVolatile(cells, target, occupiedCell);
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
        checkOpen();
        int start = indexFor(key);

        retry:
        while (true) {
            int firstTombstone = -1;

            for (int i = 0; i < cells.length; i++) {
                int index = (start + i) & mask;
                long cell = (long) CELL_HANDLE.getVolatile(cells, index);
                int state = GcraCell.state(cell);

                if (state == GcraCell.OCCUPIED) {
                    if ((long) KEYS_HANDLE.getAcquire(keys, index) == key) {
                        long currentTat = GcraCell.tat(cell);
                        if (now < currentTat - burstTolerance) {
                            return false;
                        }
                        long newTat = GcraMath.nextTat(currentTat, now, interval);
                        long newCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);
                        if (CELL_HANDLE.compareAndSet(cells, index, cell, newCell)) {
                            LAST_ACCESS_HANDLE.setRelease(lastAccess, index, now);
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

                    if (!CELL_HANDLE.compareAndSet(cells, target, targetExpected, claimingCell)) {
                        continue retry;
                    }

                    KEYS_HANDLE.setRelease(keys, target, key);
                    LAST_ACCESS_HANDLE.setRelease(lastAccess, target, now);
                    long newTat = GcraMath.newKeyTat(now, interval);
                    long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);
                    CELL_HANDLE.setVolatile(cells, target, occupiedCell);
                    return true;
                }
            }

            if (firstTombstone >= 0) {
                int target = firstTombstone;
                long tombstoneCell = GcraCell.pack(GcraCell.TOMBSTONE, 0);
                long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                if (!CELL_HANDLE.compareAndSet(cells, target, tombstoneCell, claimingCell)) {
                    continue retry;
                }

                KEYS_HANDLE.setRelease(keys, target, key);
                LAST_ACCESS_HANDLE.setRelease(lastAccess, target, now);
                long newTat = GcraMath.newKeyTat(now, interval);
                long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);
                CELL_HANDLE.setVolatile(cells, target, occupiedCell);
                return true;
            }

            return false;
        }
    }

    boolean tryAcquireCell(int index, long expectedKey, long now) {
        long currentCell = (long) CELL_HANDLE.getVolatile(cells, index);
        int state = GcraCell.state(currentCell);
        if (state != GcraCell.OCCUPIED) {
            return false;
        }
        if ((long) KEYS_HANDLE.getAcquire(keys, index) != expectedKey) {
            return false;
        }
        long currentTat = GcraCell.tat(currentCell);
        if (now < currentTat - burstTolerance) {
            return false;
        }
        long newTat = GcraMath.nextTat(currentTat, now, interval);
        long newCell = GcraCell.pack(GcraCell.OCCUPIED, newTat);
        if (CELL_HANDLE.compareAndSet(cells, index, currentCell, newCell)) {
            LAST_ACCESS_HANDLE.setRelease(lastAccess, index, now);
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
        checkOpen();
        for (int index = 0; index < cells.length; index++) {
            cleanCell(index, now);
        }
    }

    private void cleanCell(int index, long now) {
        long currentCell = (long) CELL_HANDLE.getVolatile(cells, index);
        if (GcraCell.state(currentCell) != GcraCell.OCCUPIED) {
            return;
        }

        long last = (long) LAST_ACCESS_HANDLE.getAcquire(lastAccess, index);
        if (now <= last || now - last < evictionTimeout) {
            return;
        }

        long evictingCell = GcraCell.pack(GcraCell.EVICTING, 0);
        if (CELL_HANDLE.compareAndSet(cells, index, currentCell, evictingCell)) {
            KEYS_HANDLE.setRelease(keys, index, 0L);
            LAST_ACCESS_HANDLE.setRelease(lastAccess, index, 0L);

            long tombstoneCell = GcraCell.pack(GcraCell.TOMBSTONE, 0);
            CELL_HANDLE.setVolatile(cells, index, tombstoneCell);
        }
    }

    @Override
    public void close() {
        if (!lifecycle.compareAndSet(OPEN, CLOSING)) {
            return;
        }
        running = false;
        cleaner.interrupt();
        try {
            cleaner.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lifecycle.set(CLOSED);
    }
}