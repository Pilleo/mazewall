package demo.vulnapp.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Test interop: Java cannot name Kotlin's `native` package. Actual tokens are never invented. */
public final class NativeCleanupFault implements AutoCloseable {
    private final Object facade;
    private final Object original;
    private final Object originalFs;
    private final Method setEngine;
    private final Set<Object> held = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    public final IllegalStateException sentinel = new IllegalStateException("native acquired-resource cleanup sentinel");
    public volatile boolean failClose;

    public NativeCleanupFault() throws Throwable {
        Class<?> facadeClass = Class.forName("io.mazewall.platform.native.LinuxNative");
        Class<?> engineClass = Class.forName("io.mazewall.platform.native.NativeEngine");
        Class<?> fsClass = Class.forName("io.mazewall.platform.native.NativeFileSystem");
        facade = facadeClass.getField("INSTANCE").get(null);
        original = invoke(facade, facadeClass.getMethod("getEngineIdentity"), null);
        originalFs = invoke(original, engineClass.getMethod("getFileSystem"), null);
        setEngine = facadeClass.getMethod("setEngine", engineClass);
        Object fs = Proxy.newProxyInstance(fsClass.getClassLoader(), new Class<?>[] {fsClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && failClose) {
                        held.add(args[0]);
                        throw sentinel;
                    }
                    return invoke(originalFs, method, args);
                });
        Object captured = Proxy.newProxyInstance(engineClass.getClassLoader(), new Class<?>[] {engineClass},
                (proxy, method, args) -> method.getName().equals("getFileSystem")
                        ? fs : invoke(original, method, args));
        invoke(facade, setEngine, new Object[] {captured});
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    public int heldDescriptorCount() { return held.size(); }

    @Override public void close() throws Exception {
        failClose = false;
        try {
            invoke(facade, setEngine, new Object[] {original});
            // Tokens still belong to core's failed retirement owner, not this observer.
            // The disposable JVM's actual exit reclaims them; never claim a clean core drain.
            held.clear();
        } catch (Error | Exception failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError(failure); }
    }
}
