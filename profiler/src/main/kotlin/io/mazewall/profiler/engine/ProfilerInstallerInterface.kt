package io.mazewall.profiler.engine

import io.mazewall.NativeEngine
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.profiler.internal.ProfilerSocket
import java.util.concurrent.CountDownLatch

internal interface ProfilerInstallerInterface {
    /** The real coordinator rechecks the owning session after its blocking handshake. */
    fun forSession(checkActive: () -> Unit): ProfilerInstallerInterface = this

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

internal class RealProfilerInstaller(
    private val native: NativeEngine,
    private val userNotifSupported: Boolean = Platform.featureMatrix.seccompUserNotifSupported,
    private val checkActive: () -> Unit = {},
) : ProfilerInstallerInterface {
    override fun forSession(checkActive: () -> Unit): ProfilerInstallerInterface = RealProfilerInstaller(native, userNotifSupported, checkActive)

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
            startTraceListener = startTraceListener,
        )
    }
}
