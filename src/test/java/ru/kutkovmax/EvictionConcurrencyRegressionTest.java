package ru.kutkovmax;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EvictionConcurrencyRegressionTest {

    private GcraTable table;

    static Stream<Arguments> factories() {
        return Stream.of(
                Arguments.of("heap", LockFreeGcraLimiter.HEAP_FACTORY),
                Arguments.of("offHeap", LockFreeGcraLimiter.OFF_HEAP_FACTORY)
        );
    }

    private static GcraTable create(
            LockFreeGcraLimiter.TableFactory factory,
            int capacity,
            long interval,
            long burstTolerance,
            long evictionTimeout
    ) {
        return factory.create(
                capacity,
                interval,
                burstTolerance,
                evictionTimeout
        );
    }

    @AfterEach
    void tearDown() {
        if (table != null) {
            table.close();
            table = null;
        }
    }

    private static int indexFor(long key, int mask) {
        long x = key;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdl;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53l;
        x ^= x >>> 33;
        return ((int) x) & mask;
    }

    private static long[] findCollisionKeys(int capacity, int count) {
        int mask = capacity - 1;

        Map<Integer, List<Long>> byBucket = new HashMap<>();

        for (long key = 1; key < 100_000_000L; key++) {
            int bucket = indexFor(key, mask);

            List<Long> group = byBucket.computeIfAbsent(
                    bucket,
                    ignored -> new ArrayList<>()
            );

            group.add(key);

            if (group.size() >= count) {
                long[] result = new long[count];

                for (int i = 0; i < count; i++) {
                    result[i] = group.get(i);
                }

                return result;
            }
        }

        throw new IllegalStateException(
                "Could not find " + count +
                        " colliding keys for capacity " + capacity
        );
    }

    private static boolean tryAcquireCell(
            GcraTable table,
            int index,
            long expectedKey,
            long now
    ) {
        try {
            var method = table.getClass().getDeclaredMethod(
                    "tryAcquireCell",
                    int.class,
                    long.class,
                    long.class
            );

            method.setAccessible(true);

            return (boolean) method.invoke(
                    table,
                    index,
                    expectedKey,
                    now
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "Could not invoke tryAcquireCell",
                    e
            );
        }
    }

    /**
     * Regression:
     *
     * Linear probing cannot use EMPTY as a deletion marker.
     *
     * Example:
     *
     *     [A][B][C][EMPTY]
     *
     * After evicting A:
     *
     *     [EMPTY][B][C][EMPTY]
     *
     * Lookup(B) must not stop at the first EMPTY slot.
     *
     * This test makes only A stale. B and C remain fresh, therefore
     * clean() is expected to evict A but keep B and C.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void evictionMustNotBreakProbeChain(
            String name,
            LockFreeGcraLimiter.TableFactory factory
    ) {
        int capacity = 8;
        long interval = 100;
        long burstTolerance = 0;
        long evictionTimeout = 1_000;

        table = create(
                factory,
                capacity,
                interval,
                burstTolerance,
                evictionTimeout
        );

        long[] cluster = findCollisionKeys(capacity, 3);

        long keyA = cluster[0];
        long keyB = cluster[1];
        long keyC = cluster[2];

        int idxA = table.findOrClaim(keyA, 0);
        int idxB = table.findOrClaim(keyB, 0);
        int idxC = table.findOrClaim(keyC, 0);

        assertTrue(idxA >= 0);
        assertTrue(idxB >= 0);
        assertTrue(idxC >= 0);

        assertEquals(
                (idxA + 1) & (capacity - 1),
                idxB,
                "B must immediately follow A in the probe chain"
        );

        assertEquals(
                (idxB + 1) & (capacity - 1),
                idxC,
                "C must immediately follow B in the probe chain"
        );

        /*
         * A is deliberately made stale.
         *
         * B and C are touched immediately before clean(), so they must
         * remain alive.
         */
        assertTrue(table.tryAcquire(keyA, 0));
        assertTrue(table.tryAcquire(keyB, evictionTimeout));
        assertTrue(table.tryAcquire(keyC, evictionTimeout));

        /*
         * A is stale because:
         *
         *     evictionTimeout - 0 >= evictionTimeout
         *
         * B/C are fresh because:
         *
         *     evictionTimeout - evictionTimeout == 0
         */
        table.clean(evictionTimeout);

        /*
         * B and C must still be reachable.
         *
         * If A was changed to EMPTY, normal linear probing stops at A's
         * slot and incorrectly reports B/C as absent.
         */
        int foundB = table.findOrClaim(keyB, evictionTimeout);
        int foundC = table.findOrClaim(keyC, evictionTimeout);

        assertEquals(
                idxB,
                foundB,
                "Evicting A must not make B unreachable"
        );

        assertEquals(
                idxC,
                foundC,
                "Evicting A must not make C unreachable"
        );

        /*
         * B must retain its GCRA state.
         *
         * It was acquired at evictionTimeout and burstTolerance == 0,
         * therefore another request at exactly the same time must fail.
         */
        assertFalse(
                table.tryAcquire(keyB, evictionTimeout),
                "Evicting A must not reset B's GCRA state"
        );

        assertFalse(
                table.tryAcquire(keyC, evictionTimeout),
                "Evicting A must not reset C's GCRA state"
        );

        /*
         * A itself must be reusable after eviction.
         */
        int reinsertedA = table.findOrClaim(
                keyA,
                evictionTimeout
        );

        assertTrue(
                reinsertedA >= 0,
                "Evicted A must be reinsertable"
        );

        assertTrue(
                table.tryAcquire(keyA, evictionTimeout),
                "Reinserted A must be acquirable"
        );
    }

    /**
     * Same-key lookup must not create multiple logical entries sequentially.
     *
     * This is deliberately NOT a concurrency test. It verifies the basic
     * invariant that findOrClaim(key) returns the same slot while the key
     * remains occupied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void occupiedKeyMustHaveSingleLogicalEntry(
            String name,
            LockFreeGcraLimiter.TableFactory factory
    ) {
        int capacity = 8;
        long interval = 100;
        long burstTolerance = 0;
        long evictionTimeout = 1_000;

        table = create(
                factory,
                capacity,
                interval,
                burstTolerance,
                evictionTimeout
        );

        long key = 42L;

        int first = table.findOrClaim(key, 0);
        assertTrue(first >= 0);

        assertTrue(table.tryAcquire(key, 0));

        for (int i = 0; i < 100; i++) {
            int found = table.findOrClaim(
                    key,
                    i + 1L
            );

            assertEquals(
                    first,
                    found,
                    "An occupied key must always resolve to the same slot"
            );
        }

        /*
         * With burstTolerance == 0, a second acquire at the same timestamp
         * must not succeed.
         *
         * This verifies that repeated lookup does not create an independent
         * GCRA state.
         */
        assertFalse(
                table.tryAcquire(key, 0),
                "Repeated lookup must not create a second independent GCRA state"
        );
    }

    /**
     * After an actual eviction the key may be inserted again.
     *
     * The important invariant here is:
     *
     *     one key -> one reachable occupied entry
     *
     * We verify that repeated findOrClaim() calls resolve to one slot.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void evictedKeyCanBeReinsertedWithoutDuplicateLookup(
            String name,
            LockFreeGcraLimiter.TableFactory factory
    ) {
        int capacity = 8;
        long interval = 100;
        long burstTolerance = 0;
        long evictionTimeout = 1_000;

        table = create(
                factory,
                capacity,
                interval,
                burstTolerance,
                evictionTimeout
        );

        long key = 42L;

        assertTrue(table.tryAcquire(key, 0));

        /*
         * Make the entry stale and evict it.
         */
        table.clean(evictionTimeout);

        /*
         * Reinsert.
         */
        int first = table.findOrClaim(
                key,
                evictionTimeout + 1
        );

        assertTrue(
                first >= 0,
                "Key must be insertable after eviction"
        );

        assertTrue(
                table.tryAcquire(key, evictionTimeout + 1),
                "First acquire after eviction must succeed"
        );

        /*
         * Every subsequent lookup must resolve to the same entry.
         */
        for (int i = 0; i < 100; i++) {
            int found = table.findOrClaim(
                    key,
                    evictionTimeout + 1
            );

            assertEquals(
                    first,
                    found,
                    "Reinserted key must have exactly one reachable entry"
            );
        }

        /*
         * Same timestamp + zero tolerance => second acquire must fail.
         *
         * If another independent entry for the same key existed and lookup
         * reached it first, this assertion could incorrectly succeed.
         */
        assertFalse(
                table.tryAcquire(key, evictionTimeout + 1),
                "A reinserted key must have one GCRA state"
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void repeatedEvictionAndReinsertPreservesCluster(
            String name,
            LockFreeGcraLimiter.TableFactory factory
    ) {
        long interval = 100;
        long burstTolerance = 0;
        long evictionTimeout = 1_000;

        try (GcraTable table =
                     factory.create(8, interval, burstTolerance, evictionTimeout)) {

            long keyA = 1;
            long keyB = 2;

            /*
             * Force A and B into the same probe cluster.
             */
            assertTrue(table.tryAcquire(keyA, 0));
            assertTrue(table.tryAcquire(keyB, 0));

            /*
             * Refresh B so only A is eligible for eviction.
             */
            assertTrue(table.tryAcquire(keyB, evictionTimeout));

            /*
             * A is stale, B is fresh.
             */
            table.clean(evictionTimeout);

            /*
             * B must still be reachable.
             *
             * At time evictionTimeout + interval, the previous successful
             * acquire of B was at evictionTimeout, therefore this acquire
             * must succeed.
             */
            assertTrue(
                    table.tryAcquire(keyB, evictionTimeout + interval),
                    "B must remain reachable after A eviction"
            );

            /*
             * A can now be inserted again.
             */
            assertTrue(
                    table.tryAcquire(keyA, evictionTimeout + interval),
                    "A must be reusable after eviction"
            );

            /*
             * B must still have its own GCRA state.
             *
             * Its previous successful acquire was at
             * evictionTimeout + interval.
             *
             * An immediate second acquire must therefore be rejected.
             */
            assertFalse(
                    table.tryAcquire(keyB, evictionTimeout + interval),
                    "B must keep its GCRA state after A reinsertion"
            );
        }
    }



    /**
     * Basic concurrent same-key sanity check.
     *
     * This does NOT try to prove the stale-index ownership race.
     * It only verifies that concurrent calls do not hang and that the
     * implementation remains usable under contention.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void concurrentSameKeyOperationsMustNotHang(
            String name,
            LockFreeGcraLimiter.TableFactory factory
    ) throws Exception {
        int capacity = 64;
        long interval = 100;
        long burstTolerance = 1_000;
        long evictionTimeout = 1_000_000;

        table = create(
                factory,
                capacity,
                interval,
                burstTolerance,
                evictionTimeout
        );

        long key = 12345L;

        int threads = 8;
        int operationsPerThread = 10_000;

        ExecutorService executor =
                Executors.newFixedThreadPool(threads);

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean(false);

        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();

                        for (int i = 0;
                             i < operationsPerThread;
                             i++) {

                            table.tryAcquire(
                                    key,
                                    i
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }));
            }

            start.countDown();

            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertFalse(
                    failed.get(),
                    "Concurrent same-key operations must not fail"
            );
        } finally {
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Executor did not terminate"
            );
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void staleIndexMustNotAcquireForRecycledKey(
            String name,
            LockFreeGcraLimiter.TableFactory factory
    ) {
        try (GcraTable table = create(
                factory,
                2,
                100,
                0,
                10
        )) {
            long now = 1_000;

            long keyA = 1;
            long keyB = 3;

            int indexA = table.findOrClaim(keyA, now);

            assertTrue(
                    indexA >= 0,
                    "Key A was not inserted"
            );

            table.clean(now + 20);

            int indexB = table.findOrClaim(
                    keyB,
                    now + 20
            );

            assertEquals(
                    indexA,
                    indexB,
                    "B must reuse A's slot"
            );

            boolean mutated = tryAcquireCell(table, indexA, keyA, now + 20);
            assertFalse(mutated, "Stale index call for Key A must not succeed on slot owned by Key B");

            assertFalse(table.tryAcquire(keyB, now + 20), "Key B must preserve its GCRA state");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factories")
    void clusterMiddleEvictionPreservesRemainingKeys(
            String name,
            LockFreeGcraLimiter.TableFactory factory
    ) {
        int capacity = 16;
        long interval = 100;
        long burstTolerance = 0;
        long evictionTimeout = 1_000;

        table = create(factory, capacity, interval, burstTolerance, evictionTimeout);

        long[] cluster = findCollisionKeys(capacity, 4);
        long keyA = cluster[0];
        long keyB = cluster[1];
        long keyC = cluster[2];
        long keyD = cluster[3];

        assertTrue(table.tryAcquire(keyA, 0));
        assertTrue(table.tryAcquire(keyB, 0));
        assertTrue(table.tryAcquire(keyC, 0));
        assertTrue(table.tryAcquire(keyD, 0));

        assertTrue(table.tryAcquire(keyA, evictionTimeout));
        assertTrue(table.tryAcquire(keyD, evictionTimeout));

        table.clean(evictionTimeout);

        assertTrue(table.tryAcquire(keyA, evictionTimeout + interval), "A must remain reachable");
        assertTrue(table.tryAcquire(keyD, evictionTimeout + interval), "D must remain reachable across evicted middle");
    }
}

