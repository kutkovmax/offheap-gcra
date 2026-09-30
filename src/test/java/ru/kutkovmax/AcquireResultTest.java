package ru.kutkovmax;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcquireResultTest {

    static Stream<Arguments> limiters() {
        return Stream.of(
                Arguments.of("heap", (TableCreator) (cap, interval, burst, timeout) ->
                        LockFreeGcraLimiter.heap(cap, interval, burst, timeout)),
                Arguments.of("offHeap", (TableCreator) (cap, interval, burst, timeout) ->
                        LockFreeGcraLimiter.offHeap(cap, interval, burst, timeout))
        );
    }

    @FunctionalInterface
    interface TableCreator {
        LockFreeGcraLimiter create(int cap, long interval, long burst, long timeout);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void distinguishesAcquiredRateLimitedAndExhausted(String name, TableCreator creator) {
        // Table with capacity 2, interval 100, burstTolerance 0, timeout 1000
        try (LockFreeGcraLimiter limiter = creator.create(2, 100, 0, 1000)) {
            // First key
            assertEquals(AcquireResult.ACQUIRED, limiter.acquire(10, 0));
            // Rate-limited for key 10 at time 50 (need >= 100)
            assertEquals(AcquireResult.RATE_LIMITED, limiter.acquire(10, 50));
            assertFalse(limiter.tryAcquire(10, 50));

            // Second key fills the table (capacity = 2)
            assertEquals(AcquireResult.ACQUIRED, limiter.acquire(20, 0));

            // Third key cannot be accommodated -> CAPACITY_EXHAUSTED
            assertEquals(AcquireResult.CAPACITY_EXHAUSTED, limiter.acquire(30, 0));
            assertFalse(limiter.tryAcquire(30, 0));

            // Existing key 10 at time 100 can still acquire -> ACQUIRED
            assertEquals(AcquireResult.ACQUIRED, limiter.acquire(10, 100));

            // Evict key 20 by cleaning at time 1200
            limiter.clean(1200);

            // Now key 30 can take the slot freed by key 20
            assertEquals(AcquireResult.ACQUIRED, limiter.acquire(30, 1200));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void capacityExhaustionUnderCollisions(String name, TableCreator creator) {
        // Capacity 4
        try (LockFreeGcraLimiter limiter = creator.create(4, 10, 10, 1000)) {
            assertTrue(limiter.acquire(1, 0).isAcquired());
            assertTrue(limiter.acquire(2, 0).isAcquired());
            assertTrue(limiter.acquire(3, 0).isAcquired());
            assertTrue(limiter.acquire(4, 0).isAcquired());

            // 5th key must exhaust capacity
            assertEquals(AcquireResult.CAPACITY_EXHAUSTED, limiter.acquire(5, 0));
            assertFalse(limiter.tryAcquire(5, 0));
        }
    }
}
