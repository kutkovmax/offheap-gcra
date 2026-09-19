package ru.kutkovmax.benchmarks;

import ru.kutkovmax.LockFreeGcraLimiter;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class HeapVsOffHeapComparison {

    private static final int CAPACITY = 65_536;
    private static final long INTERVAL_NANOS = 10;
    private static final long BURST_NANOS = 1_000_000_000L;
    private static final long TIMEOUT_NANOS = 60_000_000_000L;

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================================================");
        System.out.println("   BENCHMARK MATRIX: HEAP vs OFF-HEAP (GCRA RATE LIMITER)");
        System.out.println("   Capacity: 65,536 slots | Burst: 1s | JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("==========================================================================================");
        System.out.printf("%-10s | %-12s | %-8s | %-14s | %-10s | %-10s | %-10s%n",
                "Backend", "Workload", "Threads", "Throughput", "p50 (ns)", "p99 (ns)", "p99.9 (ns)");
        System.out.println("------------------------------------------------------------------------------------------");

        // Warmup JIT
        runBenchmark(true, 1, 1, 200_000);
        runBenchmark(false, 1, 1, 200_000);

        int[] threadConfigs = {1, 4, 16};
        int[] keyConfigs = {1, 10_000};

        for (int threads : threadConfigs) {
            for (int keyCount : keyConfigs) {
                // Test Heap
                Result heapRes = runBenchmark(true, threads, keyCount, 1_000_000);
                String workload = (keyCount == 1) ? "Single Key" : "10k Keys";
                System.out.printf("%-10s | %-12s | %-8d | %,11d op/s | %10d | %10d | %10d%n",
                        "HEAP", workload, threads, heapRes.throughput, heapRes.p50, heapRes.p99, heapRes.p999);

                // Test Off-Heap
                Result offHeapRes = runBenchmark(false, threads, keyCount, 1_000_000);
                System.out.printf("%-10s | %-12s | %-8d | %,11d op/s | %10d | %10d | %10d%n",
                        "OFF-HEAP", workload, threads, offHeapRes.throughput, offHeapRes.p50, offHeapRes.p99, offHeapRes.p999);
                System.out.println("------------------------------------------------------------------------------------------");
            }
        }
        System.out.println("==========================================================================================");
    }

    private static class Result {
        long throughput;
        long p50;
        long p99;
        long p999;
    }

    private static Result runBenchmark(boolean isHeap, int threadCount, int keyCount, int totalOps) throws Exception {
        LockFreeGcraLimiter limiter = isHeap
                ? LockFreeGcraLimiter.heap(CAPACITY, INTERVAL_NANOS, BURST_NANOS, TIMEOUT_NANOS)
                : LockFreeGcraLimiter.offHeap(CAPACITY, INTERVAL_NANOS, BURST_NANOS, TIMEOUT_NANOS);

        try {
            int opsPerThread = totalOps / threadCount;
            Thread[] threads = new Thread[threadCount];
            long[][] latencies = new long[threadCount][opsPerThread];
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    long[] myLatencies = latencies[threadId];
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        return;
                    }

                    for (int i = 0; i < opsPerThread; i++) {
                        long key = (keyCount == 1) ? 42L : ThreadLocalRandom.current().nextInt(keyCount);
                        long start = System.nanoTime();
                        limiter.tryAcquire(key);
                        long end = System.nanoTime();
                        myLatencies[i] = end - start;
                    }
                });
                threads[t].start();
            }

            readyLatch.await();
            long t0 = System.nanoTime();
            startLatch.countDown();

            for (Thread t : threads) {
                t.join();
            }
            long t1 = System.nanoTime();

            // Aggregate latencies
            long[] allLatencies = new long[totalOps];
            int offset = 0;
            for (int t = 0; t < threadCount; t++) {
                System.arraycopy(latencies[t], 0, allLatencies, offset, opsPerThread);
                offset += opsPerThread;
            }
            Arrays.sort(allLatencies);

            long durationNanos = Math.max(1L, t1 - t0);
            Result res = new Result();
            res.throughput = (long) ((double) totalOps / (durationNanos / 1_000_000_000.0));
            res.p50 = allLatencies[(int) (totalOps * 0.50)];
            res.p99 = allLatencies[(int) (totalOps * 0.99)];
            res.p999 = allLatencies[(int) (totalOps * 0.999)];
            return res;
        } finally {
            limiter.close();
        }
    }
}
