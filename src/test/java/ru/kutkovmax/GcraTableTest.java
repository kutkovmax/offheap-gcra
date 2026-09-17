package ru.kutkovmax;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class GcraTableTest {

    private GcraTable table;

    static Stream<Arguments> factories() {
        return Stream.of(
                Arguments.of("heap", LockFreeGcraLimiter.HEAP_FACTORY),
                Arguments.of("offHeap", LockFreeGcraLimiter.OFF_HEAP_FACTORY)
        );
    }

    private GcraTable create(LockFreeGcraLimiter.TableFactory factory, int capacity, long interval, long burstTolerance) {
        return factory.create(capacity, interval, burstTolerance, 60_000);
    }

    @AfterEach
    void tearDown() {
        if (table != null) {
            table.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void shouldClaimEmptySlot(String name, LockFreeGcraLimiter.TableFactory factory) {
        table = create(factory, 16, 100, 0);

        int index = table.findOrClaim(42, 0);

        assertTrue(index >= 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void shouldFindExistingKey(String name, LockFreeGcraLimiter.TableFactory factory) {
        table = create(factory, 16, 100, 0);

        int first = table.findOrClaim(42, 0);
        int second = table.findOrClaim(42, 100);

        assertEquals(first, second);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void zeroCanBeValidKey(String name, LockFreeGcraLimiter.TableFactory factory) {
        table = create(factory, 16, 100, 0);

        int first = table.findOrClaim(0, 0);
        int second = table.findOrClaim(0, 100);

        assertEquals(first, second);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void shouldHandleCollisions(String name, LockFreeGcraLimiter.TableFactory factory) {
        table = create(factory, 2, 100, 0);

        int first = table.findOrClaim(1, 0);
        int second = table.findOrClaim(3, 0);

        assertNotEquals(first, second);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void shouldRejectNonPowerOfTwoCapacity(String name, LockFreeGcraLimiter.TableFactory factory) {
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(10, 100, 0, 60_000)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void concurrentClaimsForSameKeyMustReturnSameSlot(String name, LockFreeGcraLimiter.TableFactory factory)
        throws Exception {

        table = create(factory, 16, 100, 0);

        int threads = 32;

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            CountDownLatch start = new CountDownLatch(1);

            List<Future<Integer>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(
                    executor.submit(() -> {
                    start.await();
                    return table.findOrClaim(42, 0);
                }));
            }

            start.countDown();

            int expected = futures.get(0).get();

            for (Future<Integer> future : futures) {
                assertEquals(expected, future.get());
            }
        } finally {
            executor.shutdown();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void concurrentClaimsForDifferentKeysMustNotLoseEntries(String name, LockFreeGcraLimiter.TableFactory factory)
        throws Exception {
        table = create(factory, 128, 100, 0);

        int threads = 32;
        int keysPerThread = 4;

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int thread = 0; thread < threads; thread++) {
                int threadId = thread;

                futures.add(executor.submit(() -> {
                    start.await();

                    for (int i = 0; i < keysPerThread; i++) {
                        long key = (long) threadId * keysPerThread + i;
                        assertTrue(table.findOrClaim(key, 0) >= 0);
                    }

                    return null;
                }));
            }

            start.countDown();

            for (Future<?> future : futures) {
                future.get();
            }

            for (long key = 0; key < (long) threads * keysPerThread; key++) {
                assertTrue(table.findOrClaim(key, 100) >= 0);
            }
        } finally {
            executor.shutdown();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void shouldApplyGcraPerKey(String name, LockFreeGcraLimiter.TableFactory factory) {
        table = create(factory, 16, 100, 200);

        assertTrue(table.tryAcquire(42, 0));
        assertTrue(table.tryAcquire(42, 0));
        assertTrue(table.tryAcquire(42, 0));

        assertFalse(table.tryAcquire(42, 0));

        assertTrue(table.tryAcquire(42, 100));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void differentKeysMustHaveIndependentRateLimits(String name, LockFreeGcraLimiter.TableFactory factory) {
        table = create(factory, 16, 100, 0);

        assertTrue(table.tryAcquire(1, 0));
        assertFalse(table.tryAcquire(1, 0));

        assertTrue(table.tryAcquire(2, 0));
    }
}
