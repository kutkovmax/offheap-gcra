package ru.kutkovmax;

public interface GcraTable extends AutoCloseable {

    int findOrClaim(long key, long now);

    boolean tryAcquire(long key);

    boolean tryAcquire(long key, long now);

    void clean(long now);

    @Override
    void close();
}
