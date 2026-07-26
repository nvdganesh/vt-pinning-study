package com.vtstudy.app.pinning;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds ONE shared ClassLoader/Class pair so that concurrent callers all
 * contend on the SAME class initialization: the first caller blocks
 * "inside" the initializer, every other concurrent caller blocks "waiting
 * for class initialization by another thread." Both are residual pinning
 * cases JEP 491 leaves in place.
 *
 * Call POST /admin/class-init-shared/reset before each load-test run /
 * concurrency step in run_sweep.sh, or every caller after the first one
 * will simply observe an already-initialized class and return instantly --
 * silently invalidating that data point.
 */
public final class SharedClassInitHolder {

    private static final String TARGET_NAME = "com.vtstudy.app.pinning.SlowInitTarget";
    private static final String RESOURCE_PATH = "com/vtstudy/app/pinning/SlowInitTarget.class";

    private static final AtomicReference<ClassLoader> CURRENT = new AtomicReference<>(newLoader());

    private SharedClassInitHolder() {}

    public static void reset() {
        CURRENT.set(newLoader());
    }

    public static long triggerSharedInit() throws Exception {
        ClassLoader loader = CURRENT.get();
        Class<?> clazz = Class.forName(TARGET_NAME, true, loader);
        return (long) clazz.getMethod("marker").invoke(null);
    }

    private static ClassLoader newLoader() {
        byte[] bytes;
        try (InputStream is = SharedClassInitHolder.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Could not locate " + RESOURCE_PATH);
            }
            bytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new ClassLoader(SharedClassInitHolder.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (TARGET_NAME.equals(name)) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
    }
}
