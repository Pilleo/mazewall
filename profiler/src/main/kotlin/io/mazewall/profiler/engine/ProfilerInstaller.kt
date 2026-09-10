package io.mazewall.profiler.engine

import io.mazewall.core.FdOwnership
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
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = { path -> ProfilerSocket.connectDescriptorWithRetry(path) },
        startTraceListener: (
            FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch
        ) -> Unit,
    ) {
        installProfilingFilterForThread(
            ProfilerInstallationContext(LinuxNative.snapshot(), Platform.featureMatrix.seccompUserNotifSupported),
            socketPath, policy, accumulatedLogs, stackTracesMap, pathCache, processWide,
            connectWithRetry, startTraceListener = startTraceListener,
        )
    }

    fun installProfilingFilterForThread(
        context: ProfilerInstallationContext,
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = { ProfilerSocket.connectDescriptorWithRetry(it, context.native) },
        installationGate: SeccompInstallationGate = SeccompInstallationGate.Direct,
        startTraceListener: (
            FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch,
        ) -> Unit,
    ) {
        val session = ProfilerInstallerSession(
            socketPath, policy, accumulatedLogs, stackTracesMap, pathCache,
            connectWithRetry, startTraceListener, processWide, context,
        )
        session.install(installationGate)
    }
}

/** Captured once by the installer; the session retains its own native engine and close check. */
internal data class ProfilerInstallationContext(
    val native: NativeEngine,
    val userNotifSupported: Boolean,
    val onFilterApplied: () -> Unit = {},
)

internal class ProfilerInstallerSession(
    private val socketPath: String,
    private val policy: PolicyDefinition<*>,
    private val accumulatedLogs: MutableList<TraceEvent>,
    private val stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
    private val pathCache: MutableMap<String, Long>,
    private val connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val startTraceListener: (
        FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        MutableList<TraceEvent>,
        MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        MutableMap<String, Long>,
        CountDownLatch
    ) -> Unit,
    private val processWide: Boolean = false,
    private val context: ProfilerInstallationContext,
) {
    constructor(
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        startTraceListener: (
            FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            MutableList<TraceEvent>,
            MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
            MutableMap<String, Long>,
            CountDownLatch,
        ) -> Unit,
        processWide: Boolean = false,
    ) : this(
        socketPath, policy, accumulatedLogs, stackTracesMap, pathCache, connectWithRetry,
        startTraceListener, processWide, ProfilerInstallationContext(LinuxNative.snapshot(), Platform.featureMatrix.seccompUserNotifSupported),
    )

    fun install() = install(SeccompInstallationGate.Direct)

    fun install(installationGate: SeccompInstallationGate) {
        val arch = Arch.current()
        val filter = BpfFilter.build(arch, policy, profilingMode = true)

        SupervisorSeccompNotifInstaller.installOwned(
            native = context.native,
            userNotifSupported = context.userNotifSupported,
            socketPath = socketPath,
            filter = filter,
            processWide = processWide,
            connectWithRetry = connectWithRetry,
            sendDescriptor = { sockFd, fd -> ProfilerSocket.sendDescriptor(sockFd, fd, context.native) },
            onFilterApplied = context.onFilterApplied,
            installationGate = installationGate,
        ) { socketFd, readyLatch ->
            // Start background thread to listen for events from the daemon (uncontained)
            startTraceListener(socketFd, accumulatedLogs, stackTracesMap, pathCache, readyLatch)
        }
    }
}
