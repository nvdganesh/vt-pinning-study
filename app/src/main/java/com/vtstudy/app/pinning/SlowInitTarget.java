package com.vtstudy.app.pinning;

/**
 * Used to reproduce the "blocking inside a class initializer" pinning case
 * that JEP 491 explicitly leaves in place -- it only fixes synchronized
 * block/method pinning, not class-initialization blocking or waiting for
 * another thread's class initialization to complete (see JEP 491,
 * "Alternatives considered" / residual cases).
 *
 * Loading THIS class via a fresh ClassLoader (see FreshClassLoaderFactory
 * and SharedClassInitHolder) forces its <clinit> to run again, giving a
 * repeatable, sustained pinning event under load instead of a one-off
 * first-request hiccup that would otherwise vanish for the rest of a run.
 *
 * The sleep duration is read from a JVM-global system property rather than
 * a per-call argument, because static initializer blocks cannot receive
 * per-call parameters -- but System properties are visible to a class no
 * matter which ClassLoader defined it, so this still lets run_sweep.sh
 * control the delay via -Dvtstudy.classinit.sleepms=<N> at JVM startup.
 */
public class SlowInitTarget {

    public static final long INIT_DELAY_MS;

    static {
        INIT_DELAY_MS = Long.getLong("vtstudy.classinit.sleepms", 50L);
        try {
            // This sleep runs INSIDE class initialization. A virtual thread
            // blocked here cannot unmount on JDK 21 OR JDK 24 -- this is
            // the residual pinning case this endpoint exists to measure.
            Thread.sleep(INIT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static long marker() {
        return INIT_DELAY_MS;
    }
}
