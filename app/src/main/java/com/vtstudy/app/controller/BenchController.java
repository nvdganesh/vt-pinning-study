package com.vtstudy.app.controller;

import com.vtstudy.app.pinning.LocalDelayServer;
import com.vtstudy.app.pinning.PinningMechanics;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.Socket;
import java.util.Map;

/**
 * Each endpoint corresponds to exactly one row in the revised experiment
 * matrix from the reviewer response.
 *
 * Endpoint design principles:
 *   1. Only ONE variable differs between any two endpoints being compared.
 *   2. All endpoints perform equivalent allocation and response construction
 *      work so that object-creation overhead is not a confound.
 *   3. The presence or absence of a monitor is the only material difference
 *      between /io-baseline and /io-synchronized-escaped-uncontended.
 *   4. Thread.currentThread().isVirtual() is checked in the /validate
 *      endpoint to confirm the execution environment at runtime.
 */
@RestController
public class BenchController {

    private final LocalDelayServer delayServer;

    public BenchController(LocalDelayServer delayServer) {
        this.delayServer = delayServer;
    }

    // ------------------------------------------------------------------
    // CPU-bound isolation control
    // No I/O, no locking. Confirms JDK 24 is not just generally faster.
    // ------------------------------------------------------------------
    @GetMapping("/cpu-bound")
    public Map<String, Object> cpuBound(
            @RequestParam(defaultValue = "200000") long iterations) {
        return Map.of("result", PinningMechanics.cpuBound(iterations),
                      "mode", "cpu-bound",
                      "virtual", Thread.currentThread().isVirtual());
    }

    // ------------------------------------------------------------------
    // Baseline: blocking wait, no monitor.
    // Throughput ceiling -- what JDK 24 should recover toward.
    // ------------------------------------------------------------------
    @GetMapping("/io-baseline")
    public Map<String, Object> ioBaseline(
            @RequestParam(defaultValue = "50") long delayMs) throws InterruptedException {
        PinningMechanics.ioBaselineNoLock(delayMs);
        return Map.of("delayMs", delayMs,
                      "mode", "no-lock",
                      "virtual", Thread.currentThread().isVirtual());
    }

    // ------------------------------------------------------------------
    // PRIMARY experiment endpoint (corrected):
    // Escaped, uncontended intrinsic monitor + blocking wait.
    // Lock drawn from globally-reachable pool -- cannot be eliminated by
    // HotSpot escape analysis. One unique slot per request; zero contention.
    //
    // JDK 21-23: carrier pinned for delayMs -> VirtualThreadPinned JFR event.
    // JDK 24+  : virtual thread unmounts normally; zero pin events.
    // ------------------------------------------------------------------
    @GetMapping("/io-synchronized-escaped-uncontended")
    public Map<String, Object> ioSyncEscapedUncontended(
            @RequestParam(defaultValue = "50") long delayMs) throws InterruptedException {
        PinningMechanics.synchronizedEscapedUncontended(delayMs);
        return Map.of("delayMs", delayMs,
                      "mode", "synchronized-escaped-uncontended",
                      "lockPoolSeq", PinningMechanics.REQUEST_SEQ.get(),
                      "virtual", Thread.currentThread().isVirtual());
    }

    // ------------------------------------------------------------------
    // Monitor overhead control: escaped monitor, NO blocking.
    // Quantifies pure monitor acquisition cost vs baseline so that any
    // delta between this and the pinned endpoint is attributable to the
    // blocking-while-synchronized interaction specifically.
    // ------------------------------------------------------------------
    @GetMapping("/io-synchronized-escaped-noblock")
    public Map<String, Object> ioSyncEscapedNoBlock(
            @RequestParam(defaultValue = "1000") long iterations) {
        return Map.of("result", PinningMechanics.synchronizedEscapedNoBlock(iterations),
                      "mode", "synchronized-escaped-noblock",
                      "virtual", Thread.currentThread().isVirtual());
    }

    // ------------------------------------------------------------------
    // Already-fixed baseline: ReentrantLock + blocking.
    // Should be statistically identical across JDK 21 and JDK 24.
    // A significant cross-JDK difference here indicates an uncontrolled
    // variable in the environment, not a pinning effect.
    // ------------------------------------------------------------------
    @GetMapping("/io-reentrantlock")
    public Map<String, Object> ioReentrantLock(
            @RequestParam(defaultValue = "50") long delayMs) throws InterruptedException {
        PinningMechanics.reentrantLockGuarded(delayMs);
        return Map.of("delayMs", delayMs,
                      "mode", "reentrant-lock",
                      "virtual", Thread.currentThread().isVirtual());
    }

    // ------------------------------------------------------------------
    // Realistic-contention variant: shared monitor + blocking.
    // Combines pinning and mutual-exclusion queuing -- models a
    // synchronized JDBC driver or connection pool. Secondary measurement;
    // NOT the primary comparison. Report separately.
    // ------------------------------------------------------------------
    @GetMapping("/io-synchronized-shared")
    public Map<String, Object> ioSyncShared(
            @RequestParam(defaultValue = "50") long delayMs) throws InterruptedException {
        PinningMechanics.synchronizedSharedMonitor(delayMs);
        return Map.of("delayMs", delayMs,
                      "mode", "synchronized-shared-monitor",
                      "virtual", Thread.currentThread().isVirtual());
    }

    // ------------------------------------------------------------------
    // External validity: real socket I/O to the local delay server.
    // A genuine blocking TCP read -- not Thread.sleep() -- to validate
    // that synthetic findings generalize to actual network I/O.
    // The local server introduces a controlled delayMs on its side so
    // the total blocking time is comparable to the synthetic endpoints.
    // ------------------------------------------------------------------
    @GetMapping("/io-real-blocking")
    public Map<String, Object> ioRealBlocking() throws Exception {
        long start = System.nanoTime();
        try (Socket s = new Socket("127.0.0.1", delayServer.getPort())) {
            InputStream in = s.getInputStream();
            //noinspection ResultOfMethodCallIgnored
            in.read(); // blocks until the delay server responds
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return Map.of("elapsedMs", elapsedMs,
                      "mode", "real-tcp-blocking",
                      "virtual", Thread.currentThread().isVirtual());
    }

    // ------------------------------------------------------------------
    // Admin: reset lock-pool sequence counter between runs.
    // Prevents wrap-around within a run if many requests share a slot.
    // ------------------------------------------------------------------
    @PostMapping("/admin/pinning/reset-seq")
    public Map<String, Object> resetSeq() {
        PinningMechanics.resetSequence();
        return Map.of("status", "reset", "newSeq", 0);
    }

    // ------------------------------------------------------------------
    // Validation: confirms the request-handling thread is actually a
    // virtual thread at runtime (not just from configuration).
    // Call once after startup; log the result in the paper.
    // ------------------------------------------------------------------
    @GetMapping("/validate/vthread-and-monitor")
    public Map<String, Object> validateVThread() {
        boolean isVirtual = Thread.currentThread().isVirtual();
        String threadName = Thread.currentThread().getName();
        int lockPoolSize  = PinningMechanics.LOCK_POOL_SIZE;
        // Access the first pool object to confirm it is heap-reachable
        boolean poolReachable = PinningMechanics.LOCK_POOL[0] != null;
        return Map.of(
            "isVirtualThread",  isVirtual,
            "threadName",       threadName,
            "lockPoolSize",     lockPoolSize,
            "lockPoolReachable",poolReachable,
            "warning",          isVirtual ? "OK" : "NOT a virtual thread -- check spring.threads.virtual.enabled=true"
        );
    }
}
