package io.mazewall.profiler.internal.engine

import io.mazewall.platform.fd.*

import io.mazewall.profiler.api.TraceEvent

import io.mazewall.platform.fd.FdOwnership
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.platform.native.NativeEngine
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.UnsupportedKernelFeatureException
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.profiler.internal.ProfilerSocket
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import io.mazewall.ffi.networking.SeccompInstallationGate
import java.util.concurrent.CountDownLatch

/**
 * Internal orchestrator for installing profiling filters and initializing the listener.
 */
internal object ProfilerInstaller {
    fun installProfilingFilterForThread(
        context: ProfilerInstallationContext,
        socketPath: String,
        policy: PolicyDefinition<*>,
        accumulatedLogs: MutableList<TraceEvent>,
        stackTracesMap: MutableMap<TraceEvent, MutableList<Array<StackTraceElement>>>?,
        pathCache: MutableMap<String, Long>,
        processWide: Boolean = false,
        connectWithRetry: ((String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>)? = null,
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
    private val connectWithRetry: ((String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>)?,
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

    fun install() = install(SeccompInstallationGate.Direct)

    fun install(installationGate: SeccompInstallationGate) {
        val arch = Arch.current()
        val filter = BpfFilter.build(arch, policy, profilingMode = true)

        val connected: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, CountDownLatch) -> Unit = { socketFd, readyLatch ->
            startTraceListener(socketFd, accumulatedLogs, stackTracesMap, pathCache, readyLatch)
        }
        val connector = connectWithRetry
        if (connector == null) {
            SupervisorSeccompNotifInstaller.installOwned(
                native = context.native,
                userNotifSupported = context.userNotifSupported,
                socketPath = socketPath,
                filter = filter,
                processWide = processWide,
                onFilterApplied = context.onFilterApplied,
                installationGate = installationGate,
                onSocketConnected = connected,
            )
        } else {
            SupervisorSeccompNotifInstaller.installOwned(
                native = context.native,
                userNotifSupported = context.userNotifSupported,
                socketPath = socketPath,
                filter = filter,
                processWide = processWide,
                connectWithRetry = connector,
                onFilterApplied = context.onFilterApplied,
                installationGate = installationGate,
                onSocketConnected = connected,
            )
        }
    }
}
