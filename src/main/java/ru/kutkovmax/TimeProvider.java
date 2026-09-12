package ru.kutkovmax;

final class TimeProvider {

    private static final long START_TIME = System.nanoTime();

    public static long nowNanos() {
        return System.nanoTime() - START_TIME;
    }

    private TimeProvider() {
    }
}
