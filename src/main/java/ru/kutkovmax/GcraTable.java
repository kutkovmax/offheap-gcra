package ru.kutkovmax;

public interface GcraTable extends AutoCloseable {

    int findOrClaim(long key, long now);

    default AcquireResult acquire(long key) {
        return acquire(key, TimeProvider.nowNanos());
    }

    AcquireResult acquire(long key, long now);

    default boolean tryAcquire(long key) {
        return acquire(key) == AcquireResult.ACQUIRED;
    }

    default boolean tryAcquire(long key, long now) {
        return acquire(key, now) == AcquireResult.ACQUIRED;
    }

    void clean(long now);

    @Override
    void close();
}
