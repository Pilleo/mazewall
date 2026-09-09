package io.mazewall.profiler.engine

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.Arch
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeArg
import io.mazewall.ffi.NativeConstants
import io.mazewall.getFdOrThrow
import io.mazewall.profiler.internal.ProfilerSocket
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import io.mazewall.ffi.networking.SeccompInstallationGate
import java.util.concurrent.CountDownLatch

/**
 * Internal orchestrator for installing profiling filters and initializing the listener.
 */
internal object ProfilerInstaller {
    /**
     * Installs a seccomp profiling filter (SECCOMP_RET_USER_NOTIF) for the current thread.
     */
    fun installProfilingFilterForThread(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> Int = { path -> ProfilerSocket.connectWithRetry(path) },
        startTraceListener: (
            Int,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch
        ) -> Unit,
    ) {
        installProfilingFilterForThread(
            LinuxNative.snapshot(), Platform.featureMatrix.seccompUserNotifSupported,
            socketPath, policy, accumulatedLogs, stackTracesMap, pathCache, processWide,
            connectWithRetry, startTraceListener = startTraceListener,
        )
    }

    fun installProfilingFilterForThread(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> Int = { ProfilerSocket.connectWithRetry(it, native) },
        onFilterApplied: () -> Unit = {},
        startTraceListener: (
            Int,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch,
        ) -> Unit,
    ) {
        installProfilingFilterForThread(
            native, userNotifSupported, socketPath, policy, accumulatedLogs, stackTracesMap,
            pathCache, processWide, connectWithRetry, onFilterApplied, SeccompInstallationGate.Direct, startTraceListener,
        )
    }

    fun installProfilingFilterForThread(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean,
        connectWithRetry: (String) -> Int,
        onFilterApplied: () -> Unit,
        installationGate: SeccompInstallationGate,
        startTraceListener: (
            Int,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch,
        ) -> Unit,
    ) {
        val session = ProfilerInstallerSession(
            socketPath = socketPath,
            policy = policy,
            accumulatedLogs = accumulatedLogs,
            stackTracesMap = stackTracesMap,
            pathCache = pathCache,
            connectWithRetry = connectWithRetry,
            startTraceListener = startTraceListener,
            processWide = processWide,
            native = native,
            userNotifSupported = userNotifSupported,
            onFilterApplied = onFilterApplied,
        )
        session.install(installationGate)
    }
}

internal class ProfilerInstallerSession(
    private val socketPath: String,
    private val policy: PolicyDefinition<*>,
    private val accumulatedLogs: MutableList<TraceEvent>,
    private val stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
    private val pathCache: MutableMap<String, Long>,
    private val connectWithRetry: (String) -> Int,
    private val startTraceListener: (
        Int,
        MutableList<TraceEvent>,
        MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        MutableMap<String, Long>,
        CountDownLatch
    ) -> Unit,
    private val processWide: Boolean = false,
    private val native: NativeEngine,
    private val userNotifSupported: Boolean,
    private val onFilterApplied: () -> Unit = {},
) {
    constructor(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        connectWithRetry: (String) -> Int,
        startTraceListener: (
            Int,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch,
        ) -> Unit,
        processWide: Boolean = false,
    ) : this(
        socketPath, policy, accumulatedLogs, stackTracesMap, pathCache, connectWithRetry,
        startTraceListener, processWide, LinuxNative.snapshot(), Platform.featureMatrix.seccompUserNotifSupported,
    )

    fun install() = install(SeccompInstallationGate.Direct)

    fun install(installationGate: SeccompInstallationGate) {
        val arch = Arch.current()
        val filter = BpfFilter.build(arch, policy, profilingMode = true)

        SupervisorSeccompNotifInstaller.install(
            native = native,
            userNotifSupported = userNotifSupported,
            socketPath = socketPath,
            filter = filter,
            processWide = processWide,
            connectWithRetry = connectWithRetry,
            sendDescriptor = { sockFd, fd -> ProfilerSocket.sendDescriptor(sockFd, fd, native) },
            onFilterApplied = onFilterApplied,
            installationGate = installationGate,
        ) { socketFd, readyLatch ->
            // Start background thread to listen for events from the daemon (uncontained)
            startTraceListener(socketFd, accumulatedLogs, stackTracesMap, pathCache, readyLatch)
        }
    }
}
