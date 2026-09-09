package io.mazewall.enforcer.supervisor

/** Explicit owner for a permanent filter; task leases never own this lifetime. */
internal class SupervisorInstallationLifetime(
    private val owner: Thread,
    val runtime: SupervisorRuntimeContext,
    val scopingPolicy: StacktraceScopingPolicy = DefaultStacktraceScopingPolicy,
) : AutoCloseable {
    private var retired = false
    private var listener: AutoCloseable? = null
    private var watcher: Thread? = null

    @Synchronized
    fun attach(resource: AutoCloseable) {
        check(listener == null) { "validation listener already attached" }
        if (retired) resource.close() else listener = resource
    }

    @Synchronized
    fun start() {
        check(watcher == null && !retired) { "installation lifetime already started or retired" }
        watcher = Thread({
            try {
                owner.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                close()
            }
        }, "supervisor-owner-${owner.threadId()}").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun close() {
        if (retired) return
        retired = true
        runtime.close()
        try {
            listener?.close()
        } finally {
            listener = null
            if (watcher !== Thread.currentThread()) watcher?.interrupt()
        }
    }
}
