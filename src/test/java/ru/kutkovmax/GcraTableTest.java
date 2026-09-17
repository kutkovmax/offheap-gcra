package ru.kutkovmax;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GcraTableTest {

    @Test
    void shouldClaimEmptySlot() {
        GcraTable table = new GcraTable(16, 100, 0);

        int index = table.findOrClaim(42, 0);

        assertTrue(index >= 0);
    }

    @Test
    void shouldFindExistingKey() {
        GcraTable table = new GcraTable(16, 100, 0);

        int first = table.findOrClaim(42, 0);
        int second = table.findOrClaim(42, 100);

        assertEquals(first, second);
    }

    @Test
    void zeroCanBeValidKey() {
        GcraTable table = new GcraTable(16, 100, 0);

        int first = table.findOrClaim(0, 0);
        int second = table.findOrClaim(0, 100);

        assertEquals(first, second);
    }

    @Test
    void shouldHandleCollisions() {
        GcraTable table = new GcraTable(2, 100, 0);

        int first = table.findOrClaim(1, 0);
        int second = table.findOrClaim(3, 0);

        assertNotEquals(first, second);
    }

    @Test
    void shouldRejectNonPowerOfTwoCapacity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GcraTable(10, 100, 0)
        );
    }

    @Test
    void concurrentClaimsForSameKeyMustReturnSameSlot()
        throws Exception {

        GcraTable table = new GcraTable(16, 100, 0);

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

    @Test
    void concurrentClaimsForDifferentKeysMustNotLoseEntries()
        throws Exception {
        GcraTable table = new GcraTable(128, 100, 0);

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

            for (long key = 0; key < threads * keysPerThread; key++) {
                assertTrue(table.findOrClaim(key, 100) >= 0);
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldApplyGcraPerKey() {
        GcraTable table = new GcraTable(16, 100, 200);

        assertTrue(table.tryAcquire(42, 0));
        assertTrue(table.tryAcquire(42, 0));
        assertTrue(table.tryAcquire(42, 0));

        assertFalse(table.tryAcquire(42, 0));

        assertTrue(table.tryAcquire(42, 100));
    }

    @Test
    void differentKeysMustHaveIndependentRateLimits() {
        GcraTable table = new GcraTable(16, 100, 0);

        assertTrue(table.tryAcquire(1, 0));
        assertFalse(table.tryAcquire(1, 0));

        assertTrue(table.tryAcquire(2, 0));
    }
}
