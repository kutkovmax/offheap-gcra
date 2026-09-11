package ru.kutkovmax;

public final class GcraLimiter {

    private final long interval;
    private final long burstTolerance;

    private long tat;

    public GcraLimiter(long interval, long burstTolerance) {
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (burstTolerance < 0) {
            throw new IllegalArgumentException("burstTolerance must be non-negative");
        }
        this.interval = interval;
        this.burstTolerance = burstTolerance;
    }

    public synchronized boolean tryAcquire(long now) {
        if (now < tat - burstTolerance) {
            return false;
        }
        tat = Math.max(tat, now) + interval;
        return true;
    }
}