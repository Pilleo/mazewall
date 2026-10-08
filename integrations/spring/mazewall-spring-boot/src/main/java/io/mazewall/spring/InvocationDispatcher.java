package io.mazewall.spring;

/** Synchronous dispatch seam. Host implementations characterize placement, not protection. */
@FunctionalInterface
public interface InvocationDispatcher {
    Object call(Body body) throws Throwable;

    @FunctionalInterface
    interface Body {
        Object proceed() throws Throwable;
    }
}
