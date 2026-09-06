package ru.kutkovmax;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LockFreeGcraLimiterTest {

    @Test
    void allowsFirstRequest() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 0);

        assertTrue(limiter.tryAcquire(0));
    }

    @Test
    void rejectsSecondRequestTooSoonWithoutBurst() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 0);

        assertTrue(limiter.tryAcquire(0));
        assertFalse(limiter.tryAcquire(50));
        assertFalse(limiter.tryAcquire(99));
    }

    @Test
    void allowsSecondRequestAfterInterval() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 0);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(100));
        assertTrue(limiter.tryAcquire(200));
    }

    @Test
    void allowsBurstWithinTolerance() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 250);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
    }

    @Test
    void rejectsWhenBurstToleranceIsExhausted() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 250);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertFalse(limiter.tryAcquire(0));
    }

    @Test
    void burstRecoversOverTime() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 200);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        assertFalse(limiter.tryAcquire(0));

        assertTrue(limiter.tryAcquire(100));
        assertFalse(limiter.tryAcquire(100));
    }

    @Test
    void exactToleranceBoundaryIsAccepted() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 100);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        assertTrue(limiter.tryAcquire(100));
    }

    @Test
    void justBeforeToleranceBoundaryIsRejected() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 100);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));

        assertFalse(limiter.tryAcquire(99));
    }

    @Test
    void rejectsInvalidInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LockFreeGcraLimiter(0, 10)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new LockFreeGcraLimiter(-1, 10)
        );
    }

    @Test
    void rejectsInvalidBurstTolerance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LockFreeGcraLimiter(100, -1)
        );
    }

    @Test
    void behavesLikeSynchronizedReferenceImplementation() {
        GcraLimiter reference =
                new GcraLimiter(100, 200);

        LockFreeGcraLimiter lockFree =
                new LockFreeGcraLimiter(100, 200);

        long[] timestamps = {
                0,
                0,
                0,
                0,
                50,
                100,
                100,
                150,
                200,
                300,
                1_000
        };

        for (long now : timestamps) {
            assertEquals(
                    reference.tryAcquire(now),
                    lockFree.tryAcquire(now),
                    "Mismatch at timestamp " + now
            );
        }
    }

    @Test
    void supportsConcurrentAccess() throws Exception {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(1, 0);

        int threads = 8;
        int attemptsPerThread = 10_000;

        ExecutorService executor =
                Executors.newFixedThreadPool(threads);

        AtomicInteger accepted =
                new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < attemptsPerThread; j++) {
                    if (limiter.tryAcquire(j)) {
                        accepted.incrementAndGet();
                    }
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }

        executor.shutdown();

        assertTrue(accepted.get() > 0);
    }

    @Test
    void cellContainsExpectedState() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(100, 200);

        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
    }
}