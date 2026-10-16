package io.mazewall.profiler.api

import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.engine.ProfilerInstallerInterface
import io.mazewall.profiler.internal.engine.RealProfilerInstaller

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
            return ProfilerRuntime({ manager }, RealProfilerInstaller(native), { native.process.gettid() }, native)
        }
    }
}
