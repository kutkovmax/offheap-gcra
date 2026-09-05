package ru.kutkovmax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcraLimiterTest {

    @Test
    void allowsFirstRequest() {
        GcraLimiter limiter = new GcraLimiter(100, 0);

        assertTrue(limiter.tryAcquire(0));
    }

    @Test
    void rejectsSecondRequestTooSoonWithoutBurst() {
        GcraLimiter limiter = new GcraLimiter(100, 0);

        assertTrue(limiter.tryAcquire(0));
        assertFalse(limiter.tryAcquire(50));
        assertFalse(limiter.tryAcquire(99));
    }

    @Test
    void allowsSecondRequestAfterInterval() {
        GcraLimiter limiter = new GcraLimiter(100, 0);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(100));
        assertTrue(limiter.tryAcquire(200));
    }

    @Test
    void allowsBurstWithinTolerance() {
        GcraLimiter limiter = new GcraLimiter(100, 250);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
    }

    @Test
    void rejectsWhenBurstToleranceIsExhausted() {
        GcraLimiter limiter = new GcraLimiter(100, 250);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        assertFalse(limiter.tryAcquire(0));
    }

    @Test
    void burstRecoversOverTime() {
        GcraLimiter limiter = new GcraLimiter(100, 200);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        assertFalse(limiter.tryAcquire(0));

        assertTrue(limiter.tryAcquire(100));
        assertFalse(limiter.tryAcquire(100));
    }

    @Test
    void idleTimeDoesNotAccumulateBeyondBurstTolerance() {
        GcraLimiter limiter = new GcraLimiter(100, 50);

        assertTrue(limiter.tryAcquire(0));

        assertTrue(limiter.tryAcquire(1_000_000));
        assertFalse(limiter.tryAcquire(1_000_000));
    }

    @Test
    void exactBurstBoundaryIsAccepted() {
        GcraLimiter limiter = new GcraLimiter(100, 200);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        assertFalse(limiter.tryAcquire(0));

        assertTrue(limiter.tryAcquire(100));
    }

    @Test
    void requestExactlyAtToleranceBoundaryIsAccepted() {
        GcraLimiter limiter = new GcraLimiter(100, 100);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        // TAT = 200, tolerance = 100.
        // now = 100 => now == TAT - tolerance.
        assertTrue(limiter.tryAcquire(100));
    }

    @Test
    void requestJustBeforeToleranceBoundaryIsRejected() {
        GcraLimiter limiter = new GcraLimiter(100, 100);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        // TAT = 200, tolerance = 100.
        // now = 99 < 100.
        assertFalse(limiter.tryAcquire(99));
    }

    @Test
    void sustainedRateIsRespected() {
        GcraLimiter limiter = new GcraLimiter(100, 0);

        long now = 0;

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(now));
            now += 100;
        }
    }

    @Test
    void invalidIntervalIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GcraLimiter(0, 10)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new GcraLimiter(-1, 10)
        );
    }

    @Test
    void invalidBurstToleranceIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GcraLimiter(100, -1)
        );
    }
}