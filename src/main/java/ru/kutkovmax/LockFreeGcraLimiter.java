package ru.kutkovmax;

public final class LockFreeGcraLimiter implements AutoCloseable {

    @FunctionalInterface
    interface TableFactory {
        GcraTable create(int capacity, long interval, long burstTolerance, long evictionTimeout);
    }

    static final TableFactory HEAP_FACTORY = HeapGcraTable::new;
    static final TableFactory OFF_HEAP_FACTORY = OffHeapGcraTable::new;

    private final GcraTable table;

    public static LockFreeGcraLimiter heap(int capacity, long interval, long burstTolerance) {
        return new LockFreeGcraLimiter(HEAP_FACTORY, capacity, interval, burstTolerance, 60_000);
    }

    public static LockFreeGcraLimiter heap(
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        return new LockFreeGcraLimiter(HEAP_FACTORY, capacity, interval, burstTolerance, evictionTimeout);
    }

    public static LockFreeGcraLimiter offHeap(int capacity, long interval, long burstTolerance) {
        return new LockFreeGcraLimiter(OFF_HEAP_FACTORY, capacity, interval, burstTolerance, 60_000);
    }

    public static LockFreeGcraLimiter offHeap(
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        return new LockFreeGcraLimiter(OFF_HEAP_FACTORY, capacity, interval, burstTolerance, evictionTimeout);
    }

    LockFreeGcraLimiter(
            TableFactory factory,
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        GcraMath.validateCapacity(capacity);
        GcraMath.validateConfiguration(interval, burstTolerance, evictionTimeout);
        this.table = factory.create(capacity, interval, burstTolerance, evictionTimeout);
    }

    public LockFreeGcraLimiter(
            int capacity,
            long interval,
            long burstTolerance
    ) {
        this(OFF_HEAP_FACTORY, capacity, interval, burstTolerance, 60_000);
    }

    public LockFreeGcraLimiter(
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        this(OFF_HEAP_FACTORY, capacity, interval, burstTolerance, evictionTimeout);
    }

    public boolean tryAcquire(long key) {
        return table.tryAcquire(key);
    }

    boolean tryAcquire(long key, long now) {
        return table.tryAcquire(key, now);
    }

    void clean(long now) {
        table.clean(now);
    }

    @Override
    public void close() {
        table.close();
    }
}
