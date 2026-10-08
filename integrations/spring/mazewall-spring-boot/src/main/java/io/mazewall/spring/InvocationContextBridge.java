package io.mazewall.spring;

import java.util.concurrent.Callable;

/** Trusted explicit per-invocation metadata capture; never copies transactions or every ThreadLocal. */
@FunctionalInterface
public interface InvocationContextBridge {
    Callable<Object> capture(Callable<Object> invocation);

    /** Default synchronous dispatch changes no authentication or context configuration. */
    static InvocationContextBridge none() { return invocation -> invocation; }
}
