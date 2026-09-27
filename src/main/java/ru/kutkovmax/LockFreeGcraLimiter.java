package ru.kutkovmax;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class LockFreeGcraLimiter implements AutoCloseable {

    private static final long DEFAULT_EVICTION_TIMEOUT_NANOS = 60_000_000_000L; // 60s in nanos

    @FunctionalInterface
    interface TableFactory {
        GcraTable create(int capacity, long intervalNanos, long burstToleranceNanos, long evictionTimeoutNanos);
    }

    static final TableFactory HEAP_FACTORY = HeapGcraTable::new;
    static final TableFactory OFF_HEAP_FACTORY = OffHeapGcraTable::new;

    private final GcraTable table;
    private volatile AutoCloseable cleanerHandle;

    // --- Heap factory methods with Duration ---

    public static LockFreeGcraLimiter heap(int capacity, Duration interval, Duration burstTolerance) {
        return heap(capacity, interval, burstTolerance, Duration.ofNanos(DEFAULT_EVICTION_TIMEOUT_NANOS));
    }

    public static LockFreeGcraLimiter heap(
            int capacity,
            Duration interval,
            Duration burstTolerance,
            Duration evictionTimeout
    ) {
        long intervalNanos = GcraMath.toNanos(interval, "interval");
        long burstToleranceNanos = GcraMath.toNanos(burstTolerance, "burstTolerance");
        long evictionTimeoutNanos = GcraMath.toNanos(evictionTimeout, "evictionTimeout");
        return new LockFreeGcraLimiter(HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
    }

    // --- Heap factory methods with nanoseconds ---

    public static LockFreeGcraLimiter heap(int capacity, long intervalNanos, long burstToleranceNanos) {
        return new LockFreeGcraLimiter(HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, DEFAULT_EVICTION_TIMEOUT_NANOS);
    }

    public static LockFreeGcraLimiter heap(
            int capacity,
            long intervalNanos,
            long burstToleranceNanos,
            long evictionTimeoutNanos
    ) {
        return new LockFreeGcraLimiter(HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
    }

    // --- Off-heap factory methods with Duration ---

    public static LockFreeGcraLimiter offHeap(int capacity, Duration interval, Duration burstTolerance) {
        return offHeap(capacity, interval, burstTolerance, Duration.ofNanos(DEFAULT_EVICTION_TIMEOUT_NANOS));
    }

    public static LockFreeGcraLimiter offHeap(
            int capacity,
            Duration interval,
            Duration burstTolerance,
            Duration evictionTimeout
    ) {
        long intervalNanos = GcraMath.toNanos(interval, "interval");
        long burstToleranceNanos = GcraMath.toNanos(burstTolerance, "burstTolerance");
        long evictionTimeoutNanos = GcraMath.toNanos(evictionTimeout, "evictionTimeout");
        return new LockFreeGcraLimiter(OFF_HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
    }

    // --- Off-heap factory methods with nanoseconds ---

    public static LockFreeGcraLimiter offHeap(int capacity, long intervalNanos, long burstToleranceNanos) {
        return new LockFreeGcraLimiter(OFF_HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, DEFAULT_EVICTION_TIMEOUT_NANOS);
    }

    public static LockFreeGcraLimiter offHeap(
            int capacity,
            long intervalNanos,
            long burstToleranceNanos,
            long evictionTimeoutNanos
    ) {
        return new LockFreeGcraLimiter(OFF_HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
    }

    LockFreeGcraLimiter(
            TableFactory factory,
            int capacity,
            long intervalNanos,
            long burstToleranceNanos,
            long evictionTimeoutNanos
    ) {
        GcraMath.validateCapacity(capacity);
        GcraMath.validateConfiguration(intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
        this.table = factory.create(capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
    }

    // --- Constructors with Duration ---

    public LockFreeGcraLimiter(int capacity, Duration interval, Duration burstTolerance) {
        this(capacity, interval, burstTolerance, Duration.ofNanos(DEFAULT_EVICTION_TIMEOUT_NANOS));
    }

    public LockFreeGcraLimiter(
            int capacity,
            Duration interval,
            Duration burstTolerance,
            Duration evictionTimeout
    ) {
        this(
                OFF_HEAP_FACTORY,
                capacity,
                GcraMath.toNanos(interval, "interval"),
                GcraMath.toNanos(burstTolerance, "burstTolerance"),
                GcraMath.toNanos(evictionTimeout, "evictionTimeout")
        );
    }

    // --- Constructors with nanoseconds ---

    public LockFreeGcraLimiter(
            int capacity,
            long intervalNanos,
            long burstToleranceNanos
    ) {
        this(OFF_HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, DEFAULT_EVICTION_TIMEOUT_NANOS);
    }

    public LockFreeGcraLimiter(
            int capacity,
            long intervalNanos,
            long burstToleranceNanos,
            long evictionTimeoutNanos
    ) {
        this(OFF_HEAP_FACTORY, capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
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
