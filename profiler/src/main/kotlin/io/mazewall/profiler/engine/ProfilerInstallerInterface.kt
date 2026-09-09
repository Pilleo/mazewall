package io.mazewall.profiler.engine

import io.mazewall.NativeEngine
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.ffi.networking.SeccompInstallationGate
import io.mazewall.profiler.internal.ProfilerSocket
import java.util.concurrent.CountDownLatch

internal interface ProfilerInstallerInterface {
    /** The real coordinator rechecks the owning session after its blocking handshake. */
    fun forSession(checkActive: () -> Unit): ProfilerInstallerInterface = this

    /** Native installation commits under the same gate as the owning session's close. */
    fun forSession(installationGate: SeccompInstallationGate): ProfilerInstallerInterface = this

    fun installProfilingFilterForThread(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean = false,
        startTraceListener: (
            Int,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch,
        ) -> Unit,
    )
}

internal class RealProfilerInstaller private constructor(
    private val native: NativeEngine,
    private val userNotifSupported: Boolean,
    private val checkActive: () -> Unit,
    private val installationGate: SeccompInstallationGate,
) : ProfilerInstallerInterface {
    constructor(
        native: NativeEngine,
        userNotifSupported: Boolean = Platform.featureMatrix.seccompUserNotifSupported,
        checkActive: () -> Unit = {},
    ) : this(native, userNotifSupported, checkActive, SeccompInstallationGate.Direct)

    override fun forSession(checkActive: () -> Unit): ProfilerInstallerInterface = RealProfilerInstaller(native, userNotifSupported, checkActive)

    override fun forSession(installationGate: SeccompInstallationGate): ProfilerInstallerInterface = RealProfilerInstaller(native, userNotifSupported, checkActive, installationGate)

    override fun installProfilingFilterForThread(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean,
        startTraceListener: (
            Int,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch,
        ) -> Unit,
    ) {
        ProfilerInstaller.installProfilingFilterForThread(
            native = native,
            userNotifSupported = userNotifSupported,
            socketPath = socketPath,
            policy = policy,
            accumulatedLogs = accumulatedLogs,
            stackTracesMap = stackTracesMap,
            pathCache = pathCache,
            processWide = processWide,
            connectWithRetry = { ProfilerSocket.connectWithRetry(it, native) },
            onFilterApplied = checkActive,
            installationGate = installationGate,
            startTraceListener = startTraceListener,
        )
    }
}
