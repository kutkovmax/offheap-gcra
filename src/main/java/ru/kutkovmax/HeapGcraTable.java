package ru.kutkovmax;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class HeapGcraTable implements GcraTable {

    private static final VarHandle KEYS_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle CELL_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle LAST_ACCESS_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] keys;
    private final long[] cells;
    private final long[] lastAccess;

    private final int mask;

    private final long interval;
    private final long burstTolerance;
    private final long evictionTimeout;

    private final Thread cleaner;
    private volatile boolean running;

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
        this(capacity, interval, burstTolerance, 60_000);
    }

    @Override
    public int findOrClaim(long key, long now) {
        int start = indexFor(key);
        int expiredIndex = -1;

        for (int i = 0; i < cells.length; i++) {
            int index = (start + i) & mask;

            while (true) {
                long cell = (long) CELL_HANDLE.getVolatile(cells, index);
                int state = GcraCell.state(cell);

                if (state == GcraCell.OCCUPIED) {
                    if (keys[index] == key) {
                        return index;
                    }

                    long last = (long) LAST_ACCESS_HANDLE.getAcquire(lastAccess, index);
                    if (expiredIndex < 0 && now - last >= evictionTimeout) {
                        expiredIndex = index;
                    }
                    break;
                }

                if (state == GcraCell.CLAIMING || state == GcraCell.EVICTING) {
                    Thread.onSpinWait();
                    continue;
                }

                if (state == GcraCell.EMPTY) {
                    if (expiredIndex >= 0) {
                        int target = expiredIndex;
                        long targetCell = (long) CELL_HANDLE.getVolatile(cells, target);
                        if (GcraCell.state(targetCell) != GcraCell.OCCUPIED) {
                            expiredIndex = -1;
                            continue;
                        }

                        long targetLast = (long) LAST_ACCESS_HANDLE.getAcquire(lastAccess, target);
                        if (now - targetLast < evictionTimeout) {
                            expiredIndex = -1;
                            continue;
                        }

                        long targetTat = GcraCell.tat(targetCell);
                        long claimingCell = GcraCell.pack(GcraCell.CLAIMING, targetTat);

                        if (!CELL_HANDLE.compareAndSet(cells, target, targetCell, claimingCell)) {
                            expiredIndex = -1;
                            continue;
                        }

                        KEYS_HANDLE.setRelease(keys, target, key);
                        long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, now);
                        CELL_HANDLE.setVolatile(cells, target, occupiedCell);
                        return target;
                    }

                    long emptyCell = GcraCell.pack(GcraCell.EMPTY, 0);
                    if (cell != emptyCell) {
                        continue;
                    }
                    long claimingCell = GcraCell.pack(GcraCell.CLAIMING, 0);

                    if (!CELL_HANDLE.compareAndSet(cells, index, emptyCell, claimingCell)) {
                        continue;
                    }

                    KEYS_HANDLE.setRelease(keys, index, key);
                    long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, now);
                    CELL_HANDLE.setVolatile(cells, index, occupiedCell);
                    return index;
                }

                throw new IllegalStateException("Unexpected cell state: " + state);
            }
        }

        if (expiredIndex >= 0) {
            int index = expiredIndex;
            long cell = (long) CELL_HANDLE.getVolatile(cells, index);
            if (GcraCell.state(cell) != GcraCell.OCCUPIED) {
                return -1;
            }

            long last = (long) LAST_ACCESS_HANDLE.getAcquire(lastAccess, index);
            if (now - last < evictionTimeout) {
                return -1;
            }

            long tat = GcraCell.tat(cell);
            long claimingCell = GcraCell.pack(GcraCell.CLAIMING, tat);

            if (!CELL_HANDLE.compareAndSet(cells, index, cell, claimingCell)) {
                return -1;
            }

            KEYS_HANDLE.setRelease(keys, index, key);
            long occupiedCell = GcraCell.pack(GcraCell.OCCUPIED, now);
            CELL_HANDLE.setVolatile(cells, index, occupiedCell);
            return index;
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
            long currentCell = (long) CELL_HANDLE.getVolatile(cells, index);
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

            if (CELL_HANDLE.compareAndSet(cells, index, currentCell, newCell)) {
                LAST_ACCESS_HANDLE.setRelease(lastAccess, index, now);
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
        for (int index = 0; index < cells.length; index++) {
            cleanCell(index, now);
        }
    }

    private void cleanCell(int index, long now) {
        long last = (long) LAST_ACCESS_HANDLE.getAcquire(lastAccess, index);
        if (now - last < evictionTimeout) {
            return;
        }

        long currentCell = (long) CELL_HANDLE.getVolatile(cells, index);
        if (GcraCell.state(currentCell) != GcraCell.OCCUPIED) {
            return;
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
    }
}
