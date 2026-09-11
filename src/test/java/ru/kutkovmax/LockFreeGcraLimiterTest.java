package ru.kutkovmax;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LockFreeGcraLimiterTest {

    private LockFreeGcraLimiter limiter;

    static Stream<Arguments> factories() {
        return Stream.of(
                Arguments.of("heap", LockFreeGcraLimiter.HEAP_FACTORY),
                Arguments.of("offHeap", LockFreeGcraLimiter.OFF_HEAP_FACTORY)
        );
    }

    private LockFreeGcraLimiter create(
            LockFreeGcraLimiter.TableFactory factory,
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        return new LockFreeGcraLimiter(factory, capacity, interval, burstTolerance, evictionTimeout);
    }

    private LockFreeGcraLimiter create(
            LockFreeGcraLimiter.TableFactory factory,
            int capacity,
            long interval,
            long burstTolerance
    ) {
        return create(factory, capacity, interval, burstTolerance, 60_000);
    }

    @AfterEach
    void tearDown() {
        if (limiter != null) {
            limiter.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void allowsFirstRequest(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsSecondRequestTooSoonWithoutBurst(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
        assertFalse(limiter.tryAcquire(1, 50));
        assertFalse(limiter.tryAcquire(1, 99));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void allowsSecondRequestAfterInterval(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 100));
        assertTrue(limiter.tryAcquire(1, 200));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void allowsBurstWithinTolerance(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 250);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsWhenBurstToleranceIsExhausted(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 250);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertFalse(limiter.tryAcquire(1, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void burstRecoversOverTime(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 200);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));

        assertFalse(limiter.tryAcquire(1, 0));

        assertTrue(limiter.tryAcquire(1, 100));
        assertFalse(limiter.tryAcquire(1, 100));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void exactToleranceBoundaryIsAccepted(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 100);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));

        assertTrue(limiter.tryAcquire(1, 100));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void justBeforeToleranceBoundaryIsRejected(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 100);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(1, 0));

        assertFalse(limiter.tryAcquire(1, 99));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsInvalidInterval(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(
                IllegalArgumentException.class,
                () -> create(factory, 16, 0, 10)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> create(factory, 16, -1, 10)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void rejectsInvalidBurstTolerance(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(
                IllegalArgumentException.class,
                () -> create(factory, 16, 100, -1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void behavesLikeSynchronizedReferenceImplementation(String name, LockFreeGcraLimiter.TableFactory factory) {
        GcraLimiter reference =
                new GcraLimiter(100, 200);

        limiter = create(factory, 16, 100, 200);

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
                    limiter.tryAcquire(1, now),
                    "Mismatch at timestamp " + now
            );
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void supportsConcurrentAccess(String name, LockFreeGcraLimiter.TableFactory factory) throws Exception {
        limiter = create(factory, 16, 1, 0);

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void differentKeysHaveIndependentLimits(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 0);

        assertTrue(limiter.tryAcquire(1, 0));
        assertFalse(limiter.tryAcquire(1, 0));

        assertTrue(limiter.tryAcquire(2, 0));
        assertFalse(limiter.tryAcquire(2, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void sameKeyAlwaysUsesSameRateLimitState(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 16, 100, 0);

        assertTrue(limiter.tryAcquire(42, 0));
        assertFalse(limiter.tryAcquire(42, 0));

        assertTrue(limiter.tryAcquire(43, 0));
        assertFalse(limiter.tryAcquire(43, 0));

        assertTrue(limiter.tryAcquire(42, 100));
        assertTrue(limiter.tryAcquire(43, 100));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void evictsInactiveKey(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 4, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));

        limiter.clean(1_001);

        assertTrue(limiter.tryAcquire(1, 1_001));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void doesNotEvictActiveKey(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 4, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));

        limiter.clean(500);

        assertTrue(limiter.tryAcquire(1, 500));

        limiter.clean(1_000);

        assertTrue(limiter.tryAcquire(1, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void concurrentAcquirePreventsEviction(String name, LockFreeGcraLimiter.TableFactory factory) throws Exception {
        limiter = create(factory, 1, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<?> cleaner =
                    executor.submit(() -> limiter.clean(1_001));

            Future<?> acquirer =
                    executor.submit(() -> {
                        for (int i = 0; i < 1_000; i++) {
                            limiter.tryAcquire(1, 1_001L + i);
                        }
                    });

            cleaner.get();
            acquirer.get();

            assertTrue(limiter.tryAcquire(1, 2_001));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void evictionDoesNotCorruptAnotherKey(String name, LockFreeGcraLimiter.TableFactory factory) {
        limiter = create(factory, 2, 100, 0, 1_000);

        assertTrue(limiter.tryAcquire(1, 0));
        assertTrue(limiter.tryAcquire(2, 500));

        limiter.clean(1_001);

        assertTrue(limiter.tryAcquire(1, 1_001));

        assertTrue(limiter.tryAcquire(2, 1_001));
    }
}
