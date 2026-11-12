package io.mazewall.enforcer.internal.execution

/** Observational worker identity only; never overrides native authority or copies caller locals. */
internal object RuntimeWorkerContext {
    private class WorkerBinding(
        val owner: OwnedDomain,
        val scoping: FrozenStackScoping,
    )

    private val domain = ThreadLocal<WorkerBinding>()
    private val operation = ThreadLocal<OwnedInvocation<*>>()
    val current: OwnedDomain? get() = domain.get()?.owner
    val invocation: OwnedInvocation<*>? get() = operation.get()
    val scoping: FrozenStackScoping? get() = domain.get()?.scoping

    fun <T> withInvocation(
        owner: OwnedInvocation<*>,
        action: () -> T,
    ): T {
        check(invocation == null) { "Physical invocation already active" }
        operation.set(owner)
        return try {
            action()
        } finally {
            operation.remove()
        }
    }

    fun enter(
        owner: OwnedDomain,
        scoping: FrozenStackScoping,
    ) {
        check(current == null) { "Worker already belongs to a domain" }
        domain.set(WorkerBinding(owner, scoping))
    }

    fun exit() {
        domain.remove()
    }
}
