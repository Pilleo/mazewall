package io.mazewall.platform.daemon

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import java.util.concurrent.atomic.AtomicReference

/**
 * Lifecycle of a UNIX-domain listen daemon shared by seccomp, profiler, and supervisor engines.
 *
 * [ShuttingDown] does not retain [serverFd]; [UnixListenDaemonEvent.AcceptLoopFinished] carries
 * the accept-loop descriptor so teardown still closes it.
 */
public sealed interface UnixListenDaemonState {
    public object Uninitialized : UnixListenDaemonState {
        public fun listening(
            serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
            socketPath: String,
        ): Listening = Listening(serverFd, socketPath)
    }

    public data class Listening(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String,
    ) : UnixListenDaemonState {
        public fun active(): Active = Active(serverFd, socketPath)
    }

    public data class Active(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String,
    ) : UnixListenDaemonState

    public object ShuttingDown : UnixListenDaemonState

    public object Terminated : UnixListenDaemonState
}

public sealed interface UnixListenDaemonEvent {
    public data class Bound(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        val socketPath: String,
    ) : UnixListenDaemonEvent

    public data object ReadyAnnounced : UnixListenDaemonEvent

    public data class ShutdownRequested(val source: String) : UnixListenDaemonEvent

    public data class AcceptLoopFinished(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>? = null,
    ) : UnixListenDaemonEvent
}

public sealed interface UnixListenDaemonEffect {
    public data class LogListening(
        val socketPath: String,
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : UnixListenDaemonEffect

    public data object PublishReady : UnixListenDaemonEffect

    public data class LogShutdown(val source: String) : UnixListenDaemonEffect

    public data class CloseServer(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    ) : UnixListenDaemonEffect

    public data object ClearConnectionTables : UnixListenDaemonEffect

    public data object StopConnectionWorkers : UnixListenDaemonEffect
}

public data class UnixListenDaemonTransition(
    val state: UnixListenDaemonState,
    val effects: List<UnixListenDaemonEffect> = emptyList(),
)

public object UnixListenDaemonMachine {
    public fun evaluate(
        state: UnixListenDaemonState,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition {
        return when (state) {
            is UnixListenDaemonState.Uninitialized -> uninitialized(event)
            is UnixListenDaemonState.Listening -> listening(state, event)
            is UnixListenDaemonState.Active -> active(state, event)
            is UnixListenDaemonState.ShuttingDown -> shuttingDown(state, event)
            is UnixListenDaemonState.Terminated -> stay(state)
        }
    }

    /**
     * CAS-installs the next state, then interprets [UnixListenDaemonTransition.effects].
     * Same-state results still run effects (normally empty) and skip the CAS.
     */
    public fun apply(
        stateRef: AtomicReference<UnixListenDaemonState>,
        event: UnixListenDaemonEvent,
        executeEffects: (List<UnixListenDaemonEffect>) -> Unit,
    ): UnixListenDaemonTransition {
        while (true) {
            val current = stateRef.get()
            val transition = evaluate(current, event)
            if (current === transition.state || current == transition.state) {
                executeEffects(transition.effects)
                return transition
            }
            if (stateRef.compareAndSet(current, transition.state)) {
                executeEffects(transition.effects)
                return transition
            }
        }
    }

    private fun uninitialized(event: UnixListenDaemonEvent): UnixListenDaemonTransition {
        return when (event) {
            is UnixListenDaemonEvent.Bound -> UnixListenDaemonTransition(
                UnixListenDaemonState.Listening(event.serverFd, event.socketPath),
                listOf(UnixListenDaemonEffect.LogListening(event.socketPath, event.serverFd)),
            )
            is UnixListenDaemonEvent.ShutdownRequested -> UnixListenDaemonTransition(
                UnixListenDaemonState.ShuttingDown,
                listOf(UnixListenDaemonEffect.LogShutdown(event.source)),
            )
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd)
            is UnixListenDaemonEvent.ReadyAnnounced -> stay(UnixListenDaemonState.Uninitialized)
        }
    }

    private fun listening(
        state: UnixListenDaemonState.Listening,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition {
        return when (event) {
            is UnixListenDaemonEvent.ReadyAnnounced -> UnixListenDaemonTransition(
                state.active(),
                listOf(UnixListenDaemonEffect.PublishReady),
            )
            is UnixListenDaemonEvent.ShutdownRequested -> UnixListenDaemonTransition(
                UnixListenDaemonState.ShuttingDown,
                listOf(UnixListenDaemonEffect.LogShutdown(event.source)),
            )
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd ?: state.serverFd)
            is UnixListenDaemonEvent.Bound -> stay(state)
        }
    }

    private fun active(
        state: UnixListenDaemonState.Active,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition {
        return when (event) {
            is UnixListenDaemonEvent.ShutdownRequested -> UnixListenDaemonTransition(
                UnixListenDaemonState.ShuttingDown,
                listOf(UnixListenDaemonEffect.LogShutdown(event.source)),
            )
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd ?: state.serverFd)
            is UnixListenDaemonEvent.ReadyAnnounced, is UnixListenDaemonEvent.Bound -> stay(state)
        }
    }

    private fun shuttingDown(
        state: UnixListenDaemonState.ShuttingDown,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition {
        return when (event) {
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd)
            is UnixListenDaemonEvent.ShutdownRequested,
            is UnixListenDaemonEvent.ReadyAnnounced,
            is UnixListenDaemonEvent.Bound,
            -> stay(state)
        }
    }

    private fun terminate(
        serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>?,
    ): UnixListenDaemonTransition {
        val effects = buildList {
            if (serverFd != null) {
                add(UnixListenDaemonEffect.CloseServer(serverFd))
            }
            add(UnixListenDaemonEffect.ClearConnectionTables)
            add(UnixListenDaemonEffect.StopConnectionWorkers)
        }
        return UnixListenDaemonTransition(UnixListenDaemonState.Terminated, effects)
    }

    private fun stay(state: UnixListenDaemonState): UnixListenDaemonTransition =
        UnixListenDaemonTransition(state, emptyList())
}
