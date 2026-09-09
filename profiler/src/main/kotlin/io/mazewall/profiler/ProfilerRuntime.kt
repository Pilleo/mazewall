package io.mazewall.profiler

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.Tid
import io.mazewall.profiler.engine.ProfilerInstallerInterface
import io.mazewall.profiler.engine.RealProfilerInstaller
import io.mazewall.profiler.internal.ProfilerDaemonManager

/** Dependencies captured by one profiling session rather than read from mutable globals mid-run. */
internal data class ProfilerRuntime(
    val daemonManager: () -> ProfilerDaemonManager,
    val installer: ProfilerInstallerInterface,
    val tid: () -> Tid,
    val native: NativeEngine = LinuxNative.snapshot(),
    val environment: () -> ProfileEnvironment = {
        ProfileEnvironment(System.getProperty("os.version") ?: "unknown", EbpfCapability.probe())
    },
) {
    companion object {
        fun default(): ProfilerRuntime {
            val native = LinuxNative.snapshot()
            val manager = ProfilerDaemonManager(native)
            return ProfilerRuntime({ manager }, RealProfilerInstaller, { native.process.gettid() }, native)
        }
    }
}
