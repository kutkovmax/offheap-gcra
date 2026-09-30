package ru.kutkovmax.stress;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;
import ru.kutkovmax.AcquireResult;
import ru.kutkovmax.LockFreeGcraLimiter;

@JCStressTest
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Acquired before close completed")
@Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "Closed before or during acquire")
@State
public class CloseRaceStressTest {

    private final LockFreeGcraLimiter limiter;

    public CloseRaceStressTest() {
        this.limiter = LockFreeGcraLimiter.offHeap(16, 1_000_000L, 0L);
    }

    @Actor
    public void actorAcquire(I_Result r) {
        try {
            AcquireResult res = limiter.acquire(99L, 100L);
            r.r1 = (res == AcquireResult.ACQUIRED) ? 1 : 0;
        } catch (IllegalStateException e) {
            // Expected when closed
            r.r1 = 2;
        }
    }

    @Actor
    public void actorClose() {
        limiter.close();
    }
}
