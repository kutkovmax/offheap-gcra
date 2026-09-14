package ru.kutkovmax;

public final class LockFreeGcraLimiter {

    private final GcraTable table;

    public LockFreeGcraLimiter(
            int capacity,
            long interval,
            long burstTolerance
    ) {
        this.table = new GcraTable(
                capacity,
                interval,
                burstTolerance
        );
    }

    public boolean tryAcquire(long key, long now) {
        return table.tryAcquire(key, now);
    }
}