package ru.kutkovmax;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class LockFreeGcraLimiter {

    private static final VarHandle CELL_HANDLE;

    static {
        try {
            CELL_HANDLE = MethodHandles.lookup()
                    .findVarHandle(
                            LockFreeGcraLimiter.class,
                            "cell",
                            long.class
                    );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long interval;
    private final long burstTolerance;

    private volatile long cell;

    public LockFreeGcraLimiter(long interval, long burstTolerance) {
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (burstTolerance < 0) {
            throw new IllegalArgumentException("burstTolerance must be non-negative");
        }

        this.interval = interval;
        this.burstTolerance = burstTolerance;

        this.cell = GcraCell.pack(
                0,
                GcraState.OCCUPIED,
                0
        );
    }

    public boolean tryAcquire(long now) {
        while (true) {
            long currentCell =
                    (long) CELL_HANDLE.getVolatile(this);

            int version = GcraCell.version(currentCell);
            int state = GcraCell.state(currentCell);
            long currentTat = GcraCell.tat(currentCell);

            if (state != GcraState.OCCUPIED) {
                throw new IllegalStateException(
                        "Unexpected cell state: " + state
                );
            }

            if (now < currentTat - burstTolerance) {
                return false;
            }

            long newTat =
                    Math.max(currentTat, now) + interval;

            long newCell = GcraCell.pack(
                    version,
                    GcraState.OCCUPIED,
                    newTat
            );

            if (CELL_HANDLE.compareAndSet(
                    this,
                    currentCell,
                    newCell
            )) {
                return true;
            }
        }
    }
}