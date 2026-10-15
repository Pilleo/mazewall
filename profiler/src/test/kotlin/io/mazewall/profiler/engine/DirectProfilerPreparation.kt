package io.mazewall.profiler.internal.engine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.util.concurrent.CompletableFuture

/** Synchronous scripted readers are safe only in host tests, never in the production reactor. */
internal fun directProfilerPreparation(reader: ProfilerMemoryReader): ProfilerPreparation =
    ProfilerPreparation { notification, name ->
    val future = CompletableFuture<SyscallEvent<SyscallEventState.Resolved>>()
    try {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                future.complete(
                    SyscallPathResolver(reader, SessionEventLedger()).resolve(
                        SyscallEvent<SyscallEventState.Raw>(
                        Tid(notification.pid),
                        name,
                        notification.args.toList(),
                    ),
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
