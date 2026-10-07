package io.mazewall.enforcer.internal.execution

/** Observational worker identity only; never overrides native authority or copies caller locals. */
internal object RuntimeWorkerContext {
    private val domain = ThreadLocal<OwnedDomain>()
    val current: OwnedDomain? get() = domain.get()

    fun enter(owner: OwnedDomain) {
        check(current == null) { "Worker already belongs to a domain" }
        domain.set(owner)
    }

    fun exit() {
        domain.remove()
    }
}
