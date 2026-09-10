package io.mazewall.profiler.engine

import io.mazewall.platform.seccomp.SeccompNotification
import java.util.concurrent.CompletableFuture

/** Heap-only preparation submitted without doing remote-memory or filesystem I/O on the reactor. */
internal fun interface ProfilerPreparation {
    fun submit(
        notification: SeccompNotification,
        syscallName: String,
    ): ProfilerPreparationJob
}

/** Owns one preparation request; cancellation revokes publication and terminates its active helper. */
internal interface ProfilerPreparationJob : AutoCloseable {
    val result: CompletableFuture<SyscallEvent<SyscallEventState.Resolved>>
}
