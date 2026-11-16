package io.mazewall.enforcer.internal.execution

/** Observational worker identity only; never overrides native authority or copies caller locals. */
internal object RuntimeWorkerContext {
    private class WorkerBinding(
        val owner: OwnedDomain,
        val scoping: FrozenStackScoping,
        var worker: io.mazewall.platform.native.WorkerOwner? = null,
    )

    private val domain = ThreadLocal<WorkerBinding>()
    private val operation = ThreadLocal<OwnedInvocation<*>>()
    private val task = ThreadLocal<io.mazewall.platform.native.TaskLease>()
    val current: OwnedDomain? get() = domain.get()?.owner
    val invocation: OwnedInvocation<*>? get() = operation.get()
    val scoping: FrozenStackScoping? get() = domain.get()?.scoping
    val liveWorker: io.mazewall.platform.native.WorkerOwner? get() = domain.get()?.worker
    val liveTask: io.mazewall.platform.native.TaskLease? get() = task.get()

    fun bindLiveWorker(worker: io.mazewall.platform.native.WorkerOwner) {
        val binding = checkNotNull(domain.get())
        check(binding.scoping is FrozenStackScoping.Live && binding.worker == null)
        binding.worker = worker
    }

    fun <T> withLiveTask(
        lease: io.mazewall.platform.native.TaskLease,
        action: () -> T,
    ): T {
        check(liveWorker === lease.worker && liveTask == null)
        task.set(lease)
        return try {
            action()
        } finally {
            task.remove()
        }
    }

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
