package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import java.util.concurrent.CompletableFuture

/** Synchronous scripted readers are safe only in host tests, never in the production reactor. */
internal fun directProfilerPreparation(reader: ProfilerMemoryReader): ProfilerPreparation =
    ProfilerPreparation { notification, name ->
    val future = CompletableFuture<SyscallEvent<SyscallEventState.Resolved>>()
    try {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                future.complete(
                    SyscallEvent.fromResolution(
                        Tid(notification.pid),
                        name,
                        notification.args.toList(),
                        SyscallPathResolver(reader, SessionEventLedger()).resolveEvidence(Tid(notification.pid), name, notification.args),
                    ),
                )
            }
        }
    } catch (failure: Exception) {
        future.completeExceptionally(failure)
    }
    object : ProfilerPreparationJob {
        override val result = future

        override fun close() {
            result.cancel(false)
        }
    }
}
