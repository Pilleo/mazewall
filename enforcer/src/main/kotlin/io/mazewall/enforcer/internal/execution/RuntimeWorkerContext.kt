package io.mazewall.enforcer.internal.execution

/** Observational worker identity only; never overrides native authority or copies caller locals. */
internal object RuntimeWorkerContext {
    private val domain = ThreadLocal<OwnedDomain>()
    private val operation = ThreadLocal<OwnedInvocation<*>>()
    val current: OwnedDomain? get() = domain.get()
    val invocation: OwnedInvocation<*>? get() = operation.get()

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

    fun enter(owner: OwnedDomain) {
        check(current == null) { "Worker already belongs to a domain" }
        domain.set(owner)
    }

    fun exit() {
        domain.remove()
    }
}
