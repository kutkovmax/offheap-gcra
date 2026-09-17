package ru.kutkovmax;

public final class LockFreeGcraLimiter {

    private final GcraTable table;

    public LockFreeGcraLimiter(
            int capacity,
            long interval,
            long burstTolerance
    ) {
        this(capacity, interval, burstTolerance, 60_000);
    }

    public LockFreeGcraLimiter(
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        this.table = new GcraTable(
                capacity,
                interval,
                burstTolerance,
                evictionTimeout
        );
    }

    public boolean tryAcquire(long key) {
        return table.tryAcquire(key);
    }

    public boolean tryAcquire(long key, long now) {
        return table.tryAcquire(key, now);
    }

    public void clean(long now) {
        table.clean(now);
    }

    public void close() {
        table.close();
    }
}
