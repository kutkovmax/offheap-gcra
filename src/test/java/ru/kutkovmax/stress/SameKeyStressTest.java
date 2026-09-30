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
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Actor 1 acquired, Actor 2 rate limited")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Actor 2 acquired, Actor 1 rate limited")
@State
public class SameKeyStressTest {

    private final LockFreeGcraLimiter limiter;

    public SameKeyStressTest() {
        this.limiter = LockFreeGcraLimiter.offHeap(16, 1_000_000_000L, 0L);
    }

    @Actor
    public void actor1(II_Result r) {
        AcquireResult res = limiter.acquire(42L, 1_000L);
        r.r1 = (res == AcquireResult.ACQUIRED) ? 1 : 0;
    }

    @Actor
    public void actor2(II_Result r) {
        AcquireResult res = limiter.acquire(42L, 1_000L);
        r.r2 = (res == AcquireResult.ACQUIRED) ? 1 : 0;
    }
}
