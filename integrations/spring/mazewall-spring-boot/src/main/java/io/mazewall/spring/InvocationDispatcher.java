package io.mazewall.spring;

/**
 * Synchronous dispatch seam. Production boundaries require ManagedRuntimeDispatcher.
 * Arbitrary implementations are trusted host characterization via the explicitly named
 * boundary factory; they supply no ownership or kernel-protection proof. Nested bodies
 * must execute synchronously on the exact current worker/context without offload.
 */
@FunctionalInterface
public interface InvocationDispatcher {
    Object call(Body body) throws Throwable;

    @FunctionalInterface
    interface Body {
        Object proceed() throws Throwable;
    }
}
