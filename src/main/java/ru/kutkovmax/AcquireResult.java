package ru.kutkovmax;

/**
 * Result of an acquisition attempt in {@link LockFreeGcraLimiter}.
 */
public enum AcquireResult {

    /**
     * Acquisition succeeded. The operation is permitted under rate limiting rules.
     */
    ACQUIRED,

    /**
     * Acquisition was rejected because the rate limit for this key has been exceeded.
     */
    RATE_LIMITED,

    /**
     * Acquisition failed because the internal hash table capacity is fully exhausted
     * and cannot allocate a new slot for this key.
     */
    CAPACITY_EXHAUSTED;

    /**
     * Returns true if the permit was acquired, false otherwise.
     */
    public boolean isAcquired() {
        return this == ACQUIRED;
    }
}
