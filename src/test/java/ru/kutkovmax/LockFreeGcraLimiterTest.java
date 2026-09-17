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
                new LockFreeGcraLimiter(16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
    }

    @Test
    void rejectsSecondRequestTooSoonWithoutBurst() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
        assertFalse(limiter.tryAcquire(1, 50));
        assertFalse(limiter.tryAcquire(1, 99));
    }

    @Test
    void allowsSecondRequestAfterInterval() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 100));
        assertTrue(limiter.tryAcquire(1, 200));
    }

    @Test
    void allowsBurstWithinTolerance() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 250);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
    }

    @Test
    void rejectsWhenBurstToleranceIsExhausted() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 250);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertFalse(limiter.tryAcquire(1, 0));
    }

    @Test
    void burstRecoversOverTime() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 200);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));

        assertFalse(limiter.tryAcquire(1, 0));

        assertTrue(limiter.tryAcquire(1, 100));
        assertFalse(limiter.tryAcquire(1, 100));
    }

    @Test
    void exactToleranceBoundaryIsAccepted() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 100);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));

        assertTrue(limiter.tryAcquire(1, 100));
    }

    @Test
    void justBeforeToleranceBoundaryIsRejected() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 100);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));

        assertFalse(limiter.tryAcquire(1, 99));
    }

    @Test
    void rejectsInvalidInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LockFreeGcraLimiter(16, 0, 10)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new LockFreeGcraLimiter(16, -1, 10)
        );
    }

    @Test
    void rejectsInvalidBurstTolerance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LockFreeGcraLimiter(16, 100, -1)
        );
    }

    @Test
    void behavesLikeSynchronizedReferenceImplementation() {
        GcraLimiter reference =
                new GcraLimiter(100, 200);

        LockFreeGcraLimiter lockFree =
                new LockFreeGcraLimiter(16, 100, 200);

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
                    lockFree.tryAcquire(1, now),
                    "Mismatch at timestamp " + now
            );
        }
    }

    @Test
    void supportsConcurrentAccess() throws Exception {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 1, 0);

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
                    if (limiter.tryAcquire(1, j)) {
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
    void differentKeysHaveIndependentLimits() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
        assertFalse(limiter.tryAcquire(1, 0));

        assertTrue(limiter.tryAcquire(2, 0));
        assertFalse(limiter.tryAcquire(2, 0));
    }

    @Test
    void sameKeyAlwaysUsesSameRateLimitState() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(16, 100, 0);

        assertTrue(limiter.tryAcquire(42, 0));
        assertFalse(limiter.tryAcquire(42, 0));

        assertTrue(limiter.tryAcquire(43, 0));
        assertFalse(limiter.tryAcquire(43, 0));

        assertTrue(limiter.tryAcquire(42, 100));
        assertTrue(limiter.tryAcquire(43, 100));
    }

    @Test
    void evictsInactiveKey() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(4, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));

        limiter.clean(1_001);

        assertTrue(limiter.tryAcquire(1, 1_001));
    }



    @Test
    void doesNotEvictActiveKey() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(4, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));

        limiter.clean(500);

        assertTrue(limiter.tryAcquire(1, 500));

        limiter.clean(1_000);

        assertTrue(limiter.tryAcquire(1, 1_000));
    }

    @Test
    void concurrentAcquirePreventsEviction() throws Exception {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(1, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<?> cleaner =
                    executor.submit(() -> limiter.clean(1_001));

            Future<?> acquirer =
                    executor.submit(() -> {
                        for (int i = 0; i < 1_000; i++) {
                            limiter.tryAcquire(1, 1_001 + i);
                        }
                    });

            cleaner.get();
            acquirer.get();

            assertTrue(limiter.tryAcquire(1, 2_001));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evictionDoesNotCorruptAnotherKey() {
        LockFreeGcraLimiter limiter =
                new LockFreeGcraLimiter(2, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(2, 500));

        limiter.clean(1_001);

        assertTrue(limiter.tryAcquire(1, 1_001));

        assertTrue(limiter.tryAcquire(2, 1_001));
    }
}
