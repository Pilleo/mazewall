package io.mazewall.profiler

import io.mazewall.core.Tid
import io.mazewall.profiler.engine.ProfilerInstallerInterface
import io.mazewall.profiler.internal.ProfilerDaemonManager

/** Dependencies captured by one profiling session rather than read from mutable globals mid-run. */
internal data class ProfilerRuntime(
    val daemonManager: () -> ProfilerDaemonManager,
    val installer: ProfilerInstallerInterface,
    val tid: () -> Tid,
)
