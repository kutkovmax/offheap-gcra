package ru.kutkovmax.stress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import ru.kutkovmax.AcquireResult;
import ru.kutkovmax.LockFreeGcraLimiter;

@JCStressTest
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Acquired before or after clean")
@Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Rate limited before clean")
@State
public class EvictionRaceStressTest {

    private final LockFreeGcraLimiter limiter;

    public EvictionRaceStressTest() {
        // capacity 16, interval 100, burst 0, timeout 500
        this.limiter = LockFreeGcraLimiter.offHeap(16, 100L, 0L, 500L);
        this.limiter.acquire(100L, 10L);
    }

    @Actor
    public void actorAcquire(II_Result r) {
        AcquireResult res = limiter.acquire(100L, 600L);
        r.r1 = (res == AcquireResult.ACQUIRED) ? 1 : 0;
    }

    @Actor
    public void actorClean(II_Result r) {
        limiter.clean(600L);
        r.r2 = 0;
    }
}
