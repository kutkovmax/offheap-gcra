package ru.kutkovmax.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import ru.kutkovmax.LockFreeGcraLimiter;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"--enable-preview", "-Xms2g", "-Xmx2g"})
public class GcraBenchmark {

    @Param({"HEAP", "OFF_HEAP"})
    public String implementation;

    @Param({"1", "1000"})
    public int keyCount;

    private LockFreeGcraLimiter limiter;

    @Setup(Level.Trial)
    public void setup() {
        int capacity = 16384;
        long intervalNanos = 10;
        long burstToleranceNanos = 1_000_000L;
        long evictionTimeoutNanos = 60_000_000_000L;

        if ("HEAP".equalsIgnoreCase(implementation)) {
            this.limiter = LockFreeGcraLimiter.heap(capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
        } else {
            this.limiter = LockFreeGcraLimiter.offHeap(capacity, intervalNanos, burstToleranceNanos, evictionTimeoutNanos);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (limiter != null) {
            limiter.close();
        }
    }

    @Benchmark
    @Threads(1)
    public boolean benchmarkSingleThread(Blackhole bh) {
        long key = (keyCount == 1) ? 42L : ThreadLocalRandom.current().nextInt(keyCount);
        return limiter.tryAcquire(key);
    }

    @Benchmark
    @Threads(4)
    public boolean benchmark4Threads(Blackhole bh) {
        long key = (keyCount == 1) ? 42L : ThreadLocalRandom.current().nextInt(keyCount);
        return limiter.tryAcquire(key);
    }

    @Benchmark
    @Threads(16)
    public boolean benchmark16Threads(Blackhole bh) {
        long key = (keyCount == 1) ? 42L : ThreadLocalRandom.current().nextInt(keyCount);
        return limiter.tryAcquire(key);
    }

    @Benchmark
    @Threads(32)
    public boolean benchmark32Threads(Blackhole bh) {
        long key = (keyCount == 1) ? 42L : ThreadLocalRandom.current().nextInt(keyCount);
        return limiter.tryAcquire(key);
    }

    @Benchmark
    @Threads(64)
    public boolean benchmark64Threads(Blackhole bh) {
        long key = (keyCount == 1) ? 42L : ThreadLocalRandom.current().nextInt(keyCount);
        return limiter.tryAcquire(key);
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
