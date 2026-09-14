package ru.kutkovmax;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class GcraTable {

    private static final VarHandle CELL_HANDLE = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] keys;
    private final long[] cells;
    private final int mask;

    private final long interval;
    private final long burstTolerance;

    GcraTable(
            int capacity,
            long interval,
            long burstTolerance
    ) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a positive power of two");
        }

        if (interval <= 0) {
            throw new IllegalArgumentException(
                    "interval must be positive"
            );
        }

        if (burstTolerance < 0) {
            throw new IllegalArgumentException(
                    "burstTolerance must be non-negative"
            );
        }

        this.keys = new long[capacity];
        this.cells = new long[capacity];
        this.mask = capacity - 1;

        this.interval = interval;
        this.burstTolerance = burstTolerance;
    }

    int findOrClaim(long key) {
        int start = indexFor(key);

        for (int i = 0; i < cells.length; i++) {
            int index = (start + i) & mask;

            while (true) {
                long cell = (long) CELL_HANDLE.getVolatile(cells, index);

                int state = GcraCell.state(cell);
                int version = GcraCell.version(cell);

                if (state == GcraState.OCCUPIED) {
                    if (keys[index] == key) {
                        return index;
                    }

                    break;
                }

                if (state == GcraState.CLAIMING) {
                    Thread.onSpinWait();
                    continue;
                }

                if (state == GcraState.EMPTY) {
                    long claimingCell = GcraCell.pack(version, GcraState.CLAIMING, 0);

                    if (!CELL_HANDLE.compareAndSet(cells, index, cell, claimingCell)) {
                        continue;
                    }

                    // We own this slot.
                    keys[index] = key;

                    long occupiedCell = GcraCell.pack(version, GcraState.OCCUPIED, 0);

                    if (!CELL_HANDLE.compareAndSet(cells, index, claimingCell, occupiedCell)) {
                        throw new IllegalStateException("Failed to publish claimed slot");
                    }

                    return index;
                }

                throw new IllegalStateException("Unexpected cell state: " + state);
            }
        }

        return -1;
    }

    boolean tryAcquire(long key, long now) {
        int index = findOrClaim(key);

        if (index < 0) {
            return false;
        }

        return tryAcquireCell(index, now);
    }

    private boolean tryAcquireCell(int index, long now) {
        while (true) {
            long currentCell =
                    (long) CELL_HANDLE.getVolatile(cells, index);

            int state = GcraCell.state(currentCell);

            if (state != GcraState.OCCUPIED) {
                Thread.onSpinWait();
                continue;
            }

            long currentTat = GcraCell.tat(currentCell);

            if (now < currentTat - burstTolerance) {
                return false;
            }

            int version = GcraCell.version(currentCell);

            long newTat =
                    Math.max(currentTat, now) + interval;

            long newCell =
                    GcraCell.pack(
                            version,
                            GcraState.OCCUPIED,
                            newTat
                    );

            if (CELL_HANDLE.compareAndSet(
                    cells,
                    index,
                    currentCell,
                    newCell
            )) {
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
}