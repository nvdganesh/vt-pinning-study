import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * JIT compilation verification for the corrected PinningMechanics design.
 *
 * Run BEFORE the main sweep to confirm:
 *   (a) The globally-reachable lock pool is not subject to lock elimination.
 *   (b) VirtualThreadPinned JFR events are emitted per request (on JDK 21).
 *   (c) The synchronized method compiles at C2 without a "lock elim" note.
 *
 * Run with:
 *   java -XX:+PrintCompilation \
 *        -XX:+UnlockDiagnosticVMOptions \
 *        -XX:+PrintInlining \
 *        -Djdk.virtualThreadScheduler.parallelism=4 \
 *        -Djdk.virtualThreadScheduler.maxPoolSize=4 \
 *        -XX:StartFlightRecording=filename=/tmp/jit-verify.jfr,settings=profile,dumponexit=true \
 *        VerifyJIT
 *
 * Then inspect /tmp/jit-verify.jfr with:
 *   jfr print --events jdk.VirtualThreadPinned /tmp/jit-verify.jfr
 *
 * Expected on JDK 21: one VirtualThreadPinned event per request.
 * Expected on JDK 24: zero VirtualThreadPinned events.
 * In -XX:+PrintCompilation output: should NOT see "lock elim" annotation
 * on the method containing the synchronized block.
 */
public class VerifyJIT {

    private static final int    POOL_SIZE   = 65_536;
    private static final Object[] LOCK_POOL = new Object[POOL_SIZE];
    private static final AtomicInteger SEQ  = new AtomicInteger(0);

    static { for (int i = 0; i < POOL_SIZE; i++) LOCK_POOL[i] = new Object(); }

    /** This is the method whose compilation we are verifying. */
    static void pinnedCall(long delayMs) throws InterruptedException {
        int slot = SEQ.getAndIncrement() & (POOL_SIZE - 1);
        Object lock = LOCK_POOL[slot];
        synchronized (lock) {
            Thread.sleep(delayMs);
        }
    }

    public static void main(String[] args) throws Exception {
        int n = 200;
        long delayMs = 50;

        System.out.println("JDK version : " + System.getProperty("java.version"));
        System.out.println("Carrier pool: " + System.getProperty("jdk.virtualThreadScheduler.parallelism", "default"));

        // Warm up: drive pinnedCall through C1 -> C2 compilation tiers.
        // Look in -XX:+PrintCompilation output for the method name and verify
        // no "lock elim" annotation appears on the compiled version.
        System.out.println("\nWarm-up phase (100 sequential calls to drive JIT compilation)...");
        for (int i = 0; i < 100; i++) { pinnedCall(1); }
        System.out.println("Warm-up complete. Check PrintCompilation output above for 'lock elim'.");

        // Reset sequence before the measurement run.
        SEQ.set(0);

        // Measurement run.
        System.out.printf("\nMeasurement: %d concurrent virtual threads, %d ms delay each%n", n, delayMs);
        var latch   = new CountDownLatch(n);
        var errors  = new AtomicInteger(0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        long start  = System.nanoTime();

        for (int i = 0; i < n; i++) {
            executor.submit(() -> {
                try { pinnedCall(delayMs); }
                catch (Exception e) { errors.incrementAndGet(); }
                finally { latch.countDown(); }
            });
        }
        latch.await();
        executor.close();
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("Wall time : %d ms%n", wallMs);
        System.out.printf("Errors    : %d%n", errors.get());
        System.out.printf("Requests  : %d%n", n);
        System.out.println();
        System.out.println("Now inspect /tmp/jit-verify.jfr:");
        System.out.println("  jfr print --events jdk.VirtualThreadPinned /tmp/jit-verify.jfr");
        System.out.printf("  Expected on JDK 21: ~%d events (one per request)%n", n);
        System.out.println("  Expected on JDK 24: 0 events");
        System.out.println();
        System.out.printf("JIT check: look for '%s' in PrintCompilation output.%n",
            "VerifyJIT::pinnedCall");
        System.out.println("If 'lock elim' appears on that line, the monitor was eliminated.");
        System.out.println("It should NOT appear because LOCK_POOL is a static (globally reachable) field.");
    }
}
