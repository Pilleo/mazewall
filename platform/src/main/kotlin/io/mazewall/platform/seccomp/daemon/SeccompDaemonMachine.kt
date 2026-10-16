package io.mazewall.platform.seccomp.daemon

import io.mazewall.platform.daemon.UnixListenDaemonState

/** Shared listen-loop state; name retained for existing seccomp callers. */
internal typealias SeccompDaemonState = UnixListenDaemonState
