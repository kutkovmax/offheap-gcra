package ru.kutkovmax;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class CleanerScheduler {

    private static final CleanerScheduler SHARED = new CleanerScheduler();

    public static CleanerScheduler shared() {
        return SHARED;
    }

    private final ScheduledExecutorService executor;

    public CleanerScheduler() {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gcra-shared-cleaner");
            t.setDaemon(true);
            return t;
        });
    }

    public AutoCloseable schedule(GcraTable table, Duration interval) {
        return schedule(table, interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    public AutoCloseable schedule(GcraTable table, long period, TimeUnit unit) {
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                () -> {
                    try {
                        table.clean(TimeProvider.nowNanos());
                    } catch (IllegalStateException ignored) {
                        // Table closed
                    } catch (Throwable ignored) {
                    }
                },
                period,
                period,
                unit
        );
        return () -> future.cancel(false);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
