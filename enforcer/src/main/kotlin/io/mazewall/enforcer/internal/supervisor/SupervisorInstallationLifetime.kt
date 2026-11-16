package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.StacktraceScopingPolicy

internal class CapturedScopingPolicy private constructor(
    val original: StacktraceScopingPolicy,
) {
    private val handlers = java.util.Map.copyOf(original.handlers)
    val dispatch: StacktraceScopingPolicy = object : StacktraceScopingPolicy {
        override val handlers = this@CapturedScopingPolicy.handlers
    }

    fun matches(other: CapturedScopingPolicy): Boolean =
        original === other.original &&
            handlers.keys == other.handlers.keys &&
            handlers.all { (key, handler) -> handler === other.handlers[key] }

    companion object {
        fun capture(original: StacktraceScopingPolicy): CapturedScopingPolicy = CapturedScopingPolicy(original)
    }
}

/** Explicit owner for a permanent filter; task leases never own this lifetime. */
internal class SupervisorInstallationLifetime(
    private val owner: Thread,
    val runtime: SupervisorRuntimeContext,
    val capturedScoping: CapturedScopingPolicy = CapturedScopingPolicy.capture(DefaultStacktraceScopingPolicy),
) : AutoCloseable {
    private var retired = false
    private var installationFailure: Throwable? = null
    private var listener: AutoCloseable? = null
    private var watcher: Thread? = null

    @Synchronized
    fun requireReusable() {
        check(!retired && installationFailure == null) {
            "Permanent supervisor installation failed or retired; use a new platform thread"
        }
    }

    /** A permanent kernel filter remains recorded, but failed setup never grants another lease. */
    @Synchronized
    fun fail(cause: Throwable) {
        if (installationFailure == null) installationFailure = cause
        runtime.close()
        val owned = listener
        listener = null
        owned?.close()
    }

    @Synchronized
    fun attach(resource: AutoCloseable) {
        check(listener == null) { "validation listener already attached" }
        if (retired || installationFailure != null) resource.close() else listener = resource
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
