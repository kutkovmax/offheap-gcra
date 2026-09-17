package ru.kutkovmax;

public final class LockFreeGcraLimiter implements AutoCloseable {

    @FunctionalInterface
    interface TableFactory {
        GcraTable create(int capacity, long interval, long burstTolerance, long evictionTimeout);
    }

    static final TableFactory HEAP_FACTORY = HeapGcraTable::new;
    static final TableFactory OFF_HEAP_FACTORY = OffHeapGcraTable::new;

    private final GcraTable table;
    private volatile AutoCloseable cleanerHandle;

    public static LockFreeGcraLimiter heap(int capacity, long interval, long burstTolerance) {
        return new LockFreeGcraLimiter(HEAP_FACTORY, capacity, interval, burstTolerance, 60_000_000_000L);
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
        return new LockFreeGcraLimiter(OFF_HEAP_FACTORY, capacity, interval, burstTolerance, 60_000_000_000L);
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
        this(OFF_HEAP_FACTORY, capacity, interval, burstTolerance, 60_000_000_000L);
    }

    public LockFreeGcraLimiter(
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        this(OFF_HEAP_FACTORY, capacity, interval, burstTolerance, evictionTimeout);
    }

    public synchronized void scheduleEviction(long periodNanos) {
        cancelScheduledEviction();
        this.cleanerHandle = CleanerScheduler.shared().schedule(table, periodNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public synchronized void scheduleEviction(java.time.Duration interval) {
        cancelScheduledEviction();
        this.cleanerHandle = CleanerScheduler.shared().schedule(table, interval);
    }

    public synchronized void cancelScheduledEviction() {
        if (cleanerHandle != null) {
            try {
                cleanerHandle.close();
            } catch (Exception ignored) {
            }
            cleanerHandle = null;
        }
    }

    public boolean tryAcquire(long key) {
        return table.tryAcquire(key);
    }

    public boolean tryAcquire(long key, long now) {
        return table.tryAcquire(key, now);
    }

    public void clean() {
        table.clean(TimeProvider.nowNanos());
    }

    public void clean(long now) {
        table.clean(now);
    }

    @Override
    public void close() {
        cancelScheduledEviction();
        table.close();
    }
}
