package ru.kutkovmax;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanerSchedulerTest {

    @Test
    void limiterDoesNotSpawnThreadByDefault() {
        long initialCleanerThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("gcra-cleaner"))
                .count();

        for (int i = 0; i < 50; i++) {
            try (LockFreeGcraLimiter limiter = LockFreeGcraLimiter.heap(16, 100, 100)) {
                limiter.tryAcquire(1);
            }
        }

        long finalCleanerThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("gcra-cleaner"))
                .count();

        assertEquals(0, finalCleanerThreads);
    }

    @Test
    void cleanerSchedulerPeriodicallyEvicts() throws Exception {
        CleanerScheduler scheduler = new CleanerScheduler();
        CountDownLatch latch = new CountDownLatch(3);

        GcraTable mockTable = new GcraTable() {
            @Override
            public int findOrClaim(long key, long now) { return 0; }
            @Override
            public boolean tryAcquire(long key) { return true; }
            @Override
            public boolean tryAcquire(long key, long now) { return true; }
            @Override
            public void clean(long now) { latch.countDown(); }
            @Override
            public void close() {}
        };

        try (AutoCloseable task = scheduler.schedule(mockTable, 10, TimeUnit.MILLISECONDS)) {
            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "Table should have been cleaned multiple times by scheduler");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void scheduleEvictionOnLimiterCleansExpiredKeys() throws Exception {
        try (LockFreeGcraLimiter limiter = LockFreeGcraLimiter.offHeap(16, 1_000_000L, 1_000_000L, 5_000_000L)) {
            assertTrue(limiter.tryAcquire(42L));

            limiter.scheduleEviction(Duration.ofMillis(10));
            // Wait for eviction after timeout (5ms timeout + 10ms period)
            Thread.sleep(80);

            // After eviction, key should be re-acquirable as new
            assertTrue(limiter.tryAcquire(42L));
        }
    }

    @Test
    void sharedSchedulerSingleton() {
        assertNotNull(CleanerScheduler.shared());
        assertEquals(CleanerScheduler.shared(), CleanerScheduler.shared());
    }
}
