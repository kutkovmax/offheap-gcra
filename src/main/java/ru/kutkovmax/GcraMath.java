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

    static void validateRateParameters(long interval, long burstTolerance) {
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
        if (burstTolerance < 0) {
            throw new IllegalArgumentException("burstTolerance must be non-negative, got " + burstTolerance);
        }
        if (interval > GcraCell.MAX_TAT) {
            throw new IllegalArgumentException(
                    "interval exceeds 62-bit TAT range: " + interval + " > " + GcraCell.MAX_TAT);
        }
        if (burstTolerance > GcraCell.MAX_TAT) {
            throw new IllegalArgumentException(
                    "burstTolerance exceeds 62-bit TAT range: " + burstTolerance + " > " + GcraCell.MAX_TAT);
        }
    }

    static void validateConfiguration(long interval, long burstTolerance, long evictionTimeout) {
        validateRateParameters(interval, burstTolerance);
        if (evictionTimeout <= 0) {
            throw new IllegalArgumentException("evictionTimeout must be positive, got " + evictionTimeout);
        }
        if (evictionTimeout > GcraCell.MAX_TAT) {
            throw new IllegalArgumentException(
                    "evictionTimeout exceeds 62-bit TAT range: " + evictionTimeout + " > " + GcraCell.MAX_TAT);
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
            throw new ArithmeticException("now does not fit into 62-bit TAT range: " + now);
        }
        if (currentTat < 0 || currentTat > GcraCell.MAX_TAT) {
            throw new ArithmeticException("current TAT does not fit into 62-bit TAT range: " + currentTat);
        }
        return addTat(Math.max(currentTat, now), interval);
    }

    static long newKeyTat(long now, long interval) {
        return nextTat(now, now, interval);
    }

    private GcraMath() {
    }
}
