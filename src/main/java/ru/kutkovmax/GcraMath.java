package ru.kutkovmax;

final class GcraMath {

    static void validateCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        if ((capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a power of two, got " + capacity);
        }
    }

    static long toNanos(java.time.Duration duration, String paramName) {
        java.util.Objects.requireNonNull(duration, paramName + " must not be null");
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(paramName + " exceeds supported nanosecond range: " + duration, e);
        }
    }

    static void validateRateParameters(long intervalNanos, long burstToleranceNanos) {
        if (intervalNanos <= 0) {
            throw new IllegalArgumentException("interval must be positive, got " + intervalNanos);
        }
        if (burstToleranceNanos < 0) {
            throw new IllegalArgumentException("burstTolerance must be non-negative, got " + burstToleranceNanos);
        }
        if (intervalNanos > GcraCell.MAX_TAT) {
            throw new IllegalArgumentException(
                    "interval exceeds " + GcraCell.TAT_BITS + "-bit TAT range: " + intervalNanos + " > " + GcraCell.MAX_TAT);
        }
        if (burstToleranceNanos > GcraCell.MAX_TAT) {
            throw new IllegalArgumentException(
                    "burstTolerance exceeds " + GcraCell.TAT_BITS + "-bit TAT range: " + burstToleranceNanos + " > " + GcraCell.MAX_TAT);
        }
    }

    static void validateConfiguration(long intervalNanos, long burstToleranceNanos, long evictionTimeoutNanos) {
        validateRateParameters(intervalNanos, burstToleranceNanos);
        if (evictionTimeoutNanos <= 0) {
            throw new IllegalArgumentException("evictionTimeout must be positive, got " + evictionTimeoutNanos);
        }
        if (evictionTimeoutNanos > GcraCell.MAX_TAT) {
            throw new IllegalArgumentException(
                    "evictionTimeout exceeds " + GcraCell.TAT_BITS + "-bit TAT range: " + evictionTimeoutNanos + " > " + GcraCell.MAX_TAT);
        }
    }

    static long addTat(long left, long right) {
        long result = left + right;
        if (left < 0 || right < 0 || result < 0 || result > GcraCell.MAX_TAT) {
            throw new ArithmeticException(
                    "TAT overflow: " + left + " + " + right + " exceeds " + GcraCell.MAX_TAT);
        }
        return result;
    }

    static long nextTat(long currentTat, long now, long interval) {
        if (now < 0 || now > GcraCell.MAX_TAT) {
            throw new ArithmeticException("now does not fit into " + GcraCell.TAT_BITS + "-bit TAT range: " + now);
        }
        if (currentTat < 0 || currentTat > GcraCell.MAX_TAT) {
            throw new ArithmeticException("current TAT does not fit into " + GcraCell.TAT_BITS + "-bit TAT range: " + currentTat);
        }
        return addTat(Math.max(currentTat, now), interval);
    }

    static long newKeyTat(long now, long interval) {
        return nextTat(now, now, interval);
    }

    private GcraMath() {
    }
}
