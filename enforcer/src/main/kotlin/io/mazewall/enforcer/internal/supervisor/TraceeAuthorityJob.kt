package io.mazewall.enforcer.internal.supervisor

import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.seccomp.SeccompNotification
import java.util.concurrent.CompletableFuture

/** Heap-only request lease. The six registers are copied before leaving the reactor. */
internal class TraceeAuthorityJob(
    notification: SeccompNotification,
    val deadline: Deadline,
    bypassRoots: List<String>,
) : AutoCloseable {
    private val captured = notification.copy(args = notification.args.copyOf())
    val notification: SeccompNotification get() = captured.copy(args = captured.args.copyOf())
    val bypassRoots: List<String> = java.util.List.copyOf(bypassRoots)
    val prepared = CompletableFuture<TraceeAuthoritySnapshot>()
    val decision = CompletableFuture<TraceeAuthorityDecision>()

    override fun close() {
        decision.cancel(false)
    }
}

internal class TraceeAuthoritySnapshot(
    val ppid: Int,
    val path: String?,
    val resolvedPath: String?,
    val route: SupervisorRoute,
    address: ByteArray? = null,
) {
    private val bytes = address?.copyOf()
    val address: ByteArray? get() = bytes?.copyOf()
}

internal data class TraceeAuthorityDecision(
    val route: SupervisorRoute,
    val path: String?,
)
