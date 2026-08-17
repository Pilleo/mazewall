package io.mazewall.platform.seccomp.daemon

import io.mazewall.platform.daemon.UnixListenDaemonEffect
import io.mazewall.platform.daemon.UnixListenDaemonEvent
import io.mazewall.platform.daemon.UnixListenDaemonMachine
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.daemon.UnixListenDaemonTransition

/** Shared listen-loop state; name retained for existing seccomp callers. */
public typealias SeccompDaemonState = UnixListenDaemonState

internal typealias SeccompDaemonEvent = UnixListenDaemonEvent

internal typealias SeccompDaemonEffect = UnixListenDaemonEffect

internal typealias SeccompDaemonTransition = UnixListenDaemonTransition

internal object SeccompDaemonMachine {
    fun evaluate(
        state: UnixListenDaemonState,
        event: UnixListenDaemonEvent,
    ): UnixListenDaemonTransition = UnixListenDaemonMachine.evaluate(state, event)
}
