package com.vtstudy.app.pinning;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * REVIEWER CORRECTION -- lock elimination:
 *
 * The original design allocated a new Object() inside the request handler as
 * the lock target. HotSpot's escape analysis may classify such an object as
 * non-escaping and eliminate the monitorenter/monitorexit bytecodes at C2
 * tier -- meaning the benchmark could silently stop testing synchronized
 * pinning after JIT compilation without any visible signal.
 *
 * The fix pre-allocates a static array of lock objects (LOCK_POOL). Static
 * fields are always heap-reachable, so HotSpot cannot prove the objects
 * non-escaping and cannot eliminate their monitors. Each request draws a
 * unique slot via an AtomicInteger counter so there is zero contention
 * between concurrent callers -- the endpoint still isolates carrier-pinning
 * from mutual-exclusion queuing, which was the original design intent.
 *
 * Verification: run with
 *   -XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining
 * and confirm the synchronized method reaches C2 without a "lock elim"
 * annotation. The presence of exactly one VirtualThreadPinned JFR event per
 * request (observed at 50 RPS in prior runs) also constitutes runtime proof:
 * the JVM cannot emit that event for a monitor it compiled away.
 */
public final class PinningMechanics {

    private PinningMechanics() {}

    // -----------------------------------------------------------------------
    // Globally-reachable lock pool
    // Size must exceed peak concurrent requests in any single run.
    // At 200 RPS x 50ms: Little's Law gives ~10 concurrent; 65536 slots
    // handles any realistic peak. Call resetSequence() between runs.
    // -----------------------------------------------------------------------
    public  static final int           LOCK_POOL_SIZE = 65_536;
    static  final        Object[]      LOCK_POOL      = new Object[LOCK_POOL_SIZE];
    private static final AtomicInteger REQUEST_SEQ    = new AtomicInteger(0);

    static {
        for (int i = 0; i < LOCK_POOL_SIZE; i++) LOCK_POOL[i] = new Object();
    }

    public static void resetSequence() { REQUEST_SEQ.set(0); }

    // -----------------------------------------------------------------------
    // CPU-bound isolation control -- no I/O, no locking.
    // Confirms that any JDK 21 vs JDK 24 difference on the pinned endpoint
    // is not caused by a general scheduler or JIT improvement.
    // -----------------------------------------------------------------------
    public static long cpuBound(long iterations) {
        long acc = 0;
        for (long i = 0; i < iterations; i++) {
            acc += Long.rotateLeft(i, 7) ^ (i * 2654435761L);
        }
        return acc;
    }

    // -----------------------------------------------------------------------
    // Baseline: blocking wait, no lock at all.
    // Virtual thread unmounts normally on every JDK. This is the throughput
    // ceiling the JEP 491 fix should converge toward on JDK 24.
    // -----------------------------------------------------------------------
    public static void ioBaselineNoLock(long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
    }

    // -----------------------------------------------------------------------
    // PRIMARY experiment endpoint (corrected): escaped uncontended monitor
    // + blocking wait.
    //
    // Unique lock per request from the globally-reachable pool:
    //   (a) Defeats escape analysis -- monitor cannot be eliminated.
    //   (b) Zero contention -- each slot used by at most one live request.
    //
    // JDK 21-23: virtual thread pins its carrier for delayMs.
    // JDK 24+  : virtual thread unmounts; pin event count drops to zero.
    // -----------------------------------------------------------------------
    public static void synchronizedEscapedUncontended(long delayMs)
            throws InterruptedException {
        int slot = REQUEST_SEQ.getAndIncrement() & (LOCK_POOL_SIZE - 1);
        Object lock = LOCK_POOL[slot];
        synchronized (lock) {
            Thread.sleep(delayMs);
        }
    }

    // -----------------------------------------------------------------------
    // Control: escaped monitor WITHOUT blocking.
    // Same lock-pool draw, trivial CPU work inside -- no sleep, no I/O.
    // Used to separate monitor-acquisition overhead from the blocking-while-
    // synchronized interaction. Latency delta between this and baseline
    // quantifies pure monitor overhead; latency delta between this and the
    // pinned endpoint quantifies pinning specifically.
    // -----------------------------------------------------------------------
    public static long synchronizedEscapedNoBlock(long iterations) {
        int slot = REQUEST_SEQ.getAndIncrement() & (LOCK_POOL_SIZE - 1);
        Object lock = LOCK_POOL[slot];
        long result;
        synchronized (lock) { result = cpuBound(iterations); }
        return result;
    }

    // -----------------------------------------------------------------------
    // Already-fixed baseline: ReentrantLock (shared) + blocking wait.
    // Uses java.util.concurrent park -- virtual-thread-aware before JEP 491,
    // never pins. Should be statistically identical across JDK 21 and JDK 24.
    // A significant difference here indicates an uncontrolled variable, not
    // a pinning effect.
    // -----------------------------------------------------------------------
    private static final ReentrantLock REENTRANT_LOCK = new ReentrantLock();

    public static void reentrantLockGuarded(long delayMs) throws InterruptedException {
        REENTRANT_LOCK.lock();
        try { Thread.sleep(delayMs); } finally { REENTRANT_LOCK.unlock(); }
    }

    // -----------------------------------------------------------------------
    // Realistic-contention variant: shared intrinsic monitor + blocking.
    // Combines carrier-pinning AND mutual-exclusion queuing -- models a
    // synchronized JDBC driver method or a synchronized cache client.
    // NOT the primary measurement; report separately and explicitly note that
    // the two effects cannot be decomposed from this endpoint's data alone.
    // -----------------------------------------------------------------------
    private static final Object SHARED_MONITOR = new Object();

    public static void synchronizedSharedMonitor(long delayMs) throws InterruptedException {
        synchronized (SHARED_MONITOR) { Thread.sleep(delayMs); }
    }
}
