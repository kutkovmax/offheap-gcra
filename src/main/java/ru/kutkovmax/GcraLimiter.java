package ru.kutkovmax;

public final class GcraLimiter {

    private final long interval;
    private final long burstTolerance;

    private long tat;

    public GcraLimiter(long interval, long burstTolerance) {
        GcraMath.validateRateParameters(interval, burstTolerance);
        this.interval = interval;
        this.burstTolerance = burstTolerance;
    }

    public synchronized boolean tryAcquire(long now) {
        if (now < tat - burstTolerance) {
            return false;
        }
        tat = GcraMath.nextTat(tat, now, interval);
        return true;
    }
}
