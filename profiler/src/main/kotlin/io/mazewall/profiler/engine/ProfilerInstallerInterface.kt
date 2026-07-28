package io.mazewall.profiler.engine

import io.mazewall.PolicyDefinition
import java.util.concurrent.CountDownLatch

internal interface ProfilerInstallerInterface {
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
            CountDownLatch
        ) -> Unit,
    )
}

internal object RealProfilerInstaller : ProfilerInstallerInterface {
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
            CountDownLatch
        ) -> Unit,
    ) {
        ProfilerInstaller.installProfilingFilterForThread(
            socketPath = socketPath,
            policy = policy,
            accumulatedLogs = accumulatedLogs,
            stackTracesMap = stackTracesMap,
            pathCache = pathCache,
            processWide = processWide,
            startTraceListener = startTraceListener,
        )
    }
}
