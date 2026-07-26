package com.vtstudy.app.pinning;

import java.io.InputStream;

/**
 * Forces a brand-new {@code <clinit>} run on every single call by loading
 * SlowInitTarget through a fresh ClassLoader each time. Class identity in
 * the JVM is (binary name, defining ClassLoader), so even though the same
 * .class bytes are reused, the JVM treats each load as a genuinely new
 * class that has never been initialized -- meaning every concurrent caller
 * during a load test independently blocks inside its own initializer.
 *
 * This models a virtual thread "blocking inside a class initializer" --
 * one of the residual pinning cases JEP 491 (JDK 24) does not address.
 * Use /class-init-shared instead to model the OTHER residual case:
 * "waiting for class initialization triggered by another thread."
 */
public final class FreshClassLoaderFactory {

    private static final String TARGET_NAME = "com.vtstudy.app.pinning.SlowInitTarget";
    private static final String RESOURCE_PATH = "com/vtstudy/app/pinning/SlowInitTarget.class";

    private FreshClassLoaderFactory() {}

    public static long triggerFreshInit() throws Exception {
        byte[] bytes = readClassBytes();
        ClassLoader loader = new ClassLoader(FreshClassLoaderFactory.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (TARGET_NAME.equals(name)) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        Class<?> clazz = Class.forName(TARGET_NAME, true, loader);
        return (long) clazz.getMethod("marker").invoke(null);
    }

    private static byte[] readClassBytes() throws Exception {
        try (InputStream is = FreshClassLoaderFactory.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Could not locate " + RESOURCE_PATH + " on classpath");
            }
            return is.readAllBytes();
        }
    }
}
