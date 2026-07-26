import com.vtstudy.app.pinning.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sanity check, independent of Spring Boot: spin up N virtual threads
 * concurrently against each mechanism, with the virtual thread scheduler
 * deliberately pinned to a SMALL number of carriers (mirrors run_sweep.sh's
 * -Djdk.virtualThreadScheduler.parallelism flag), and confirm pinning
 * mechanisms take much longer in aggregate than the no-lock baseline at
 * the same concurrency -- this is the core effect the whole study depends
 * on actually existing, so it's worth proving on a real JVM before
 * building an entire Spring Boot + Gatling pipeline on top of it.
 */
public class SmokeTest {

    static long timeConcurrent(int n, Runnable task) throws InterruptedException {
        AtomicLong done = new AtomicLong();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        long start = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            executor.submit(() -> {
                try {
                    task.run();
                    done.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.close();
        return (System.nanoTime() - start) / 1_000_000;
    }

    public static void main(String[] args) throws Exception {
        int concurrency = 200;
        long delayMs = 20;

        System.out.println("JDK: " + System.getProperty("java.version"));
        System.out.println("Carrier parallelism: " + System.getProperty("jdk.virtualThreadScheduler.parallelism"));
        System.out.println("Concurrency=" + concurrency + " delayMs=" + delayMs);
        System.out.println();

        long baseline = timeConcurrent(concurrency, () -> {
            try { PinningMechanics.ioBaselineNoLock(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        System.out.println("io-baseline (no lock):              " + baseline + " ms");

        long uniqueLock = timeConcurrent(concurrency, () -> {
            try { PinningMechanics.synchronizedPinnedUniqueLock(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        System.out.println("synchronized, unique lock (pinned): " + uniqueLock + " ms");

        long sharedLock = timeConcurrent(concurrency, () -> {
            try { PinningMechanics.synchronizedPinnedSharedLock(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        System.out.println("synchronized, shared lock (pinned+contended): " + sharedLock + " ms");

        long reentrant = timeConcurrent(concurrency, () -> {
            try { PinningMechanics.reentrantLockGuarded(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        System.out.println("ReentrantLock (never pins):          " + reentrant + " ms");

        SharedClassInitHolder.reset();
        long classInitShared = timeConcurrent(concurrency, () -> {
            try { SharedClassInitHolder.triggerSharedInit(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("class-init-shared (residual pin):    " + classInitShared + " ms");

        System.out.println();
        double ratio = (double) uniqueLock / baseline;
        System.out.printf("Pinning slowdown ratio (unique-lock / baseline): %.1fx%n", ratio);
        if (ratio < 1.5) {
            System.out.println("WARNING: expected a large slowdown ratio on this JDK version with a small");
            System.out.println("carrier pool. If this is JDK 24+, that's EXPECTED (JEP 491). If this is");
            System.out.println("JDK <=23, something is off -- check -Djdk.virtualThreadScheduler.parallelism");
            System.out.println("is actually small relative to 'concurrency' above.");
        } else {
            System.out.println("Confirms the pinning effect is real and measurable with this harness.");
        }
    }
}
