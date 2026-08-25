package io.mazewall.core

/**
 * Explicit semantic execution scopes for Tier E syscall attribution.
 *
 * ```kotlin
 * MazewallContext.withContext(ContextId.of(PDF_PARSE)) { parser.parse(data) }
 * ```
 *
 * Scopes are strictly per-platform-thread state: entering a scope records the context on the
 * calling OS thread, and every syscall that thread makes while the scope is active is later
 * attributed to it by the eBPF enrichment plane.
 *
 * # Platform threads only
 *
 * Invoking [withContext] on a virtual thread fails closed with [IllegalStateException].
 * Rationale: seccomp/eBPF task state binds to the underlying carrier LWP; a virtual thread
 * that parks mid-scope leaves its label on the carrier, and the next virtual thread scheduled
 * onto that carrier would inherit a fact about somebody else's work — wrong attribution, not
 * unknown attribution. See `docs/internals/designs/profiler/tier-e-design.md` §11 risk 5.
 *
 * # Semantics
 *
 * * Nesting restores correctly: [withContext] saves the previous value and restores it in a
 *   `finally` block, so exceptions unwind without leaking the inner scope.
 * * The initial value on any fresh thread is [ContextId.UNKNOWN]. Syscalls made outside any
 *   scope — including before this library ever sees the thread — are UNKNOWN by design;
 *   unknown is data, never an error.
 *
 * # Trust boundary
 *
 * Context values carry the `UNTRUSTED ATTRIBUTION METADATA` contract documented on
 * [ContextId]: tracee-controlled, forgeable after compromise, and consumed exclusively by
 * the profiling/detection plane. This API is never an enforcement input.
 *
 * Kernel-visible transition emission (the native marker downcall) arrives with the FFM bridge
 * client (WP-08); this type is intentionally pure-JVM until that gate.
 */
public object MazewallContext {

    private val current: ThreadLocal<ContextId> = ThreadLocal.withInitial { ContextId.UNKNOWN }

    /**
     * The context currently associated with the calling platform thread.
     */
    public fun current(): ContextId = current.get()

    /**
     * Runs [block] with [context] recorded as the calling thread's semantic scope, restoring
     * the previous scope afterwards — even when [block] throws.
     *
     * @throws IllegalStateException when invoked from a virtual thread; no state is changed
     *   in that case.
     */
    public fun <T> withContext(
        context: ContextId,
        block: () -> T,
    ): T {
        if (Thread.currentThread().isVirtual) {
            throw IllegalStateException(
                "MazewallContext is restricted to platform threads: a virtual thread parking " +
                    "mid-scope would leave its context on the carrier LWP and mis-attribute " +
                    "the next virtual thread scheduled there.",
            )
        }
        val previous = current.get()
        current.set(context)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }
}
