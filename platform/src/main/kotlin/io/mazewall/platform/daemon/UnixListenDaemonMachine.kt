package io.mazewall.platform.daemon

import io.mazewall.core.FdOwnership
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
            serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            socketPath: String,
        ): Listening = Listening(serverFd, socketPath)
    }

    public data class Listening(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        val socketPath: String,
    ) : UnixListenDaemonState {
        public fun active(): Active = Active(serverFd, socketPath)
    }

    public data class Active(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        val socketPath: String,
    ) : UnixListenDaemonState

    public object ShuttingDown : UnixListenDaemonState

    public object Terminated : UnixListenDaemonState
}

internal sealed interface UnixListenDaemonEvent {
    public data class Bound(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        val socketPath: String,
    ) : UnixListenDaemonEvent

    public data object ReadyAnnounced : UnixListenDaemonEvent

    public data class ShutdownRequested(
        val source: String,
    ) : UnixListenDaemonEvent

    public data class AcceptLoopFinished(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null,
    ) : UnixListenDaemonEvent
}

internal sealed interface UnixListenDaemonEffect {
    public data class LogListening(
        val socketPath: String,
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) : UnixListenDaemonEffect

    public data object PublishReady : UnixListenDaemonEffect

    public data class LogShutdown(
        val source: String,
    ) : UnixListenDaemonEffect

    public data class CloseServer(
        val serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) : UnixListenDaemonEffect

    public data object StopConnectionWorkers : UnixListenDaemonEffect

    /** Waits for interrupted connection workers before owned descriptors are released. */
    public data object AwaitConnectionWorkers : UnixListenDaemonEffect

    /** Close the engine-owned client and notification descriptors before forgetting them. */
    public data object CloseTrackedConnections : UnixListenDaemonEffect

    public data object ClearConnectionTables : UnixListenDaemonEffect
}

internal data class UnixListenDaemonTransition(
    val state: UnixListenDaemonState,
    val effects: List<UnixListenDaemonEffect> = emptyList(),
    val outcome: UnixListenDaemonOutcome,
)

/** Makes retained and rejected daemon lifecycle events explicit to the engine. */
internal sealed interface UnixListenDaemonOutcome {
    data object Advanced : UnixListenDaemonOutcome

    data class BenignObservation(
        val name: String,
    ) : UnixListenDaemonOutcome

    data class ProtocolViolation(
        val state: UnixListenDaemonState,
        val event: UnixListenDaemonEvent,
    ) : UnixListenDaemonOutcome

    data class PostTerminalObservation(
        val event: UnixListenDaemonEvent,
    ) : UnixListenDaemonOutcome

    data object Terminated : UnixListenDaemonOutcome
}

internal object UnixListenDaemonMachine {
    internal fun evaluate(
        state: UnixListenDaemonState,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition {
        return when (state) {
            is UnixListenDaemonState.Uninitialized -> uninitialized(event)
            is UnixListenDaemonState.Listening -> listening(state, event)
            is UnixListenDaemonState.Active -> active(state, event)
            is UnixListenDaemonState.ShuttingDown -> shuttingDown(state, event)
            is UnixListenDaemonState.Terminated -> postTerminal(event)
        }
    }

    /**
     * CAS-installs the next state, then interprets [UnixListenDaemonTransition.effects].
     * Same-state results still run effects (normally empty) and skip the CAS.
     */
    internal fun apply(
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
                UnixListenDaemonOutcome.Advanced,
            )
            is UnixListenDaemonEvent.ShutdownRequested -> UnixListenDaemonTransition(
                UnixListenDaemonState.ShuttingDown,
                listOf(UnixListenDaemonEffect.LogShutdown(event.source)),
                UnixListenDaemonOutcome.Advanced,
            )
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd)
            is UnixListenDaemonEvent.ReadyAnnounced -> violation(UnixListenDaemonState.Uninitialized, event)
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
                UnixListenDaemonOutcome.Advanced,
            )
            is UnixListenDaemonEvent.ShutdownRequested -> UnixListenDaemonTransition(
                UnixListenDaemonState.ShuttingDown,
                listOf(UnixListenDaemonEffect.LogShutdown(event.source)),
                UnixListenDaemonOutcome.Advanced,
            )
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd ?: state.serverFd)
            is UnixListenDaemonEvent.Bound -> violation(state, event)
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
                UnixListenDaemonOutcome.Advanced,
            )
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd ?: state.serverFd)
            is UnixListenDaemonEvent.ReadyAnnounced -> observe(state, "duplicate readiness announcement")
            is UnixListenDaemonEvent.Bound -> violation(state, event)
        }
    }

    private fun shuttingDown(
        state: UnixListenDaemonState.ShuttingDown,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition {
        return when (event) {
            is UnixListenDaemonEvent.AcceptLoopFinished -> terminate(event.serverFd)
            is UnixListenDaemonEvent.ShutdownRequested -> observe(state, "duplicate shutdown request")
            is UnixListenDaemonEvent.ReadyAnnounced -> observe(state, "readiness announced during shutdown")
            is UnixListenDaemonEvent.Bound -> violation(state, event)
        }
    }

    private fun terminate(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?): UnixListenDaemonTransition {
        val effects = buildList {
            if (serverFd != null) {
                add(UnixListenDaemonEffect.CloseServer(serverFd))
            }
            add(UnixListenDaemonEffect.StopConnectionWorkers)
            add(UnixListenDaemonEffect.AwaitConnectionWorkers)
            add(UnixListenDaemonEffect.CloseTrackedConnections)
            add(UnixListenDaemonEffect.ClearConnectionTables)
        }
        return UnixListenDaemonTransition(UnixListenDaemonState.Terminated, effects, UnixListenDaemonOutcome.Terminated)
    }

    private fun observe(
        state: UnixListenDaemonState,
        name: String,
    ): UnixListenDaemonTransition = UnixListenDaemonTransition(state, emptyList(), UnixListenDaemonOutcome.BenignObservation(name))

    private fun violation(
        state: UnixListenDaemonState,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition {
        val rejected = (event as? UnixListenDaemonEvent.Bound)?.serverFd
        val active = when (state) {
            is UnixListenDaemonState.Listening -> state.serverFd
            is UnixListenDaemonState.Active -> state.serverFd
            is UnixListenDaemonState.Uninitialized,
            is UnixListenDaemonState.ShuttingDown,
            is UnixListenDaemonState.Terminated,
            -> null
        }
        val transition = terminate(active)
        val effects = buildList {
            if (rejected != null && rejected != active) add(UnixListenDaemonEffect.CloseServer(rejected))
            addAll(transition.effects)
        }
        return UnixListenDaemonTransition(transition.state, effects, UnixListenDaemonOutcome.ProtocolViolation(state, event))
    }

    private fun postTerminal(event: UnixListenDaemonEvent): UnixListenDaemonTransition {
        val effects = when (event) {
            is UnixListenDaemonEvent.Bound -> listOf(UnixListenDaemonEffect.CloseServer(event.serverFd))
            is UnixListenDaemonEvent.ReadyAnnounced,
            is UnixListenDaemonEvent.ShutdownRequested,
            is UnixListenDaemonEvent.AcceptLoopFinished,
            -> emptyList()
        }
        return UnixListenDaemonTransition(UnixListenDaemonState.Terminated, effects, UnixListenDaemonOutcome.PostTerminalObservation(event))
    }
}
