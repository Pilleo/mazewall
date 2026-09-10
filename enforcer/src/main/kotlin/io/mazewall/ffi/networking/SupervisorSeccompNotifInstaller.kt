package io.mazewall.ffi.networking

import io.mazewall.BpfNativeCache
import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.Platform
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.Arch
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeArg
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.getFdOrThrow
import io.mazewall.seccomp.*
import java.lang.foreign.MemorySegment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified orchestrator for installing seccomp notification filters and establishing
 * socket handshakes with the supervisor/profiler daemon.
 *
 * This implementation replicates the coordinator pattern from the original profiler
 * to prevent deadlocks when supervising critical JVM operations (like open/openat).
 */
public object SupervisorSeccompNotifInstaller {
    private val defaultConnect: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = { SupervisorSocketUtils.connectDescriptorWithRetry(it) }
    private val defaultSend: (Int, Int) -> Boolean = { socket, listener -> SupervisorSocketUtils.sendDescriptor(socket, listener) }

    private fun synchronizeProcessFilter(
        native: NativeEngine,
        arch: Arch,
        dummyBpf: List<BpfInstruction>,
        installationGate: SeccompInstallationGate,
    ) {
        val dummyProg = BpfNativeCache.getOrCompute(dummyBpf, native)
        val tsyncRes = installationGate.commit {
            native.raw.syscall(
                arch.seccompSyscallNumber.toLong(),
                NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_TSYNC.toLong()),
                NativeArg.MemoryArg(dummyProg),
            )
        }
        when (tsyncRes) {
            is LinuxNative.SyscallResult.Error ->
                throw IllegalStateException(
                    "Process-wide profiling TSYNC failed: ${
                        io.mazewall.seccomp.PureJavaBpfEngine.tsyncFailureDetail(tsyncRes.errno, null)
                    }",
                )

            is LinuxNative.SyscallResult.Success ->
                if (tsyncRes.value > 0L) {
                    throw IllegalStateException(
                        "Process-wide profiling TSYNC failed: ${
                            io.mazewall.seccomp.PureJavaBpfEngine.tsyncFailureDetail(null, tsyncRes.value)
                        }",
                    )
                }
        }
    }

    private fun startCoordinator(
        native: NativeEngine,
        socketPath: String,
        listenerFdVal: AtomicReference<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>?>,
        handshake: SeccompInstallHandshake,
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        sendDescriptor: (Int, Int) -> Boolean,
        onSocketConnected: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, CountDownLatch) -> Unit,
    ): Thread =
        Thread {
            try {
                val transport = SeccompInstallTransport(native, handshake)
                // Wait for the tracee thread to complete the seccomp installation
                handshake.await(handshake.installed)

                // Publish the successful irreversible kernel stage even if connect or
                // readiness subsequently fails. The callback captures the tracee's holder.
                handshake.publishApplied()
                handshake.checkActive()

                // Connect to socket and send the descriptor (runs uncontained)
                val socketFd = if (connectWithRetry === defaultConnect) transport.connect(socketPath) else connectWithRetry(socketPath)
                var socketTransferred = false
                try {
                    handshake.checkActive()
                    // The coordinator claims the local listener only when ready to send it.
                    // Until then the failing installer may reclaim it; never close under sendmsg.
                    val fdRef = checkNotNull(listenerFdVal.getAndSet(null)) { "Seccomp listener handoff cancelled" }
                    try {
                        val sent = if (sendDescriptor === defaultSend) transport.send(socketFd, fdRef.value) else sendDescriptor(socketFd.value, fdRef.value)
                        check(sent) { "Failed to send seccomp listener FD to daemon" }
                    } finally {
                        native.fileSystem.close(fdRef).getOrThrow("close transferred seccomp listener")
                    }
                    handshake.advance(SeccompInstallStage.Transferred)
                    handshake.checkActive()

                    val readyLatch = CountDownLatch(1)

                    // Start validation/event listener thread (which will run uncontained)
                    socketTransferred = true
                    onSocketConnected(socketFd, readyLatch)

                    // Wait until the listener is fully initialized and ready
                    handshake.await(readyLatch)
                } catch (expectedCoordinatorFailure: Exception) {
                    if (!socketTransferred) {
                        native.fileSystem.close(socketFd).getOrThrow("close failed coordinator socket")
                    }
                    throw expectedCoordinatorFailure
                } catch (expectedCoordinatorError: Error) {
                    if (!socketTransferred) {
                        native.fileSystem.close(socketFd).getOrThrow("close failed coordinator socket")
                    }
                    throw expectedCoordinatorError
                }

                // Handshake is fully configured, wake up tracee thread
                handshake.advance(SeccompInstallStage.Ready)
            } catch (expectedCoordinatorFailure: Exception) {
                handshake.fail(expectedCoordinatorFailure)
            } catch (expectedCoordinatorError: Error) {
                handshake.fail(expectedCoordinatorError)
            }
        }.apply {
            isDaemon = true
            name = "seccomp-install-coordinator"
            start()
        }

    @Deprecated("Raw descriptor callbacks are disabled; use installOwned")
    public fun install(
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> Int = { path -> SupervisorSocketUtils.connectWithRetry(path) },
        sendDescriptor: (Int, Int) -> Boolean = { sockFd, fd -> SupervisorSocketUtils.sendDescriptor(sockFd, fd) },
        onFilterApplied: () -> Unit = {},
        onSocketConnected: (socketFd: Int, readyLatch: CountDownLatch) -> Unit,
    ) {
        error("Raw descriptor callbacks are disabled; use installOwned")
    }

    @Deprecated("Raw descriptor callbacks are disabled; use installOwned")
    public fun install(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean,
        connectWithRetry: (String) -> Int,
        sendDescriptor: (Int, Int) -> Boolean,
        onFilterApplied: () -> Unit = {},
        onSocketConnected: (socketFd: Int, readyLatch: CountDownLatch) -> Unit,
    ) {
        error("Raw descriptor callbacks are disabled; use installOwned")
    }

    @Deprecated("Raw descriptor callbacks are disabled; use installOwned")
    public fun install(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean,
        connectWithRetry: (String) -> Int,
        sendDescriptor: (Int, Int) -> Boolean,
        onFilterApplied: () -> Unit,
        installationGate: SeccompInstallationGate,
        onSocketConnected: (socketFd: Int, readyLatch: CountDownLatch) -> Unit,
    ) {
        error("Raw descriptor callbacks are disabled; use installOwned")
    }

    public fun installOwned(
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = defaultConnect,
        sendDescriptor: (Int, Int) -> Boolean = defaultSend,
        onFilterApplied: () -> Unit = {},
        onSocketConnected: (socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, readyLatch: CountDownLatch) -> Unit,
    ) {
        installOwned(
            LinuxNative.snapshot(),
            Platform.featureMatrix.seccompUserNotifSupported,
            socketPath,
            filter,
            processWide,
            connectWithRetry,
            sendDescriptor,
            onFilterApplied,
            onSocketConnected,
        )
    }

    /**
     * Installation under one captured native authority, including the uncontained coordinator.
     * [onSocketConnected] takes ownership of its socket on entry, including callback failure.
     * Custom connect/send callbacks are fault-injection seams. Production uses the defaults,
     * whose nonblocking native I/O and readiness checks share the installation deadline.
     */
    @io.mazewall.MazewallInternal
    public fun installOwned(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean,
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = defaultConnect,
        sendDescriptor: (Int, Int) -> Boolean = defaultSend,
        onFilterApplied: () -> Unit = {},
        onSocketConnected: (socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, readyLatch: CountDownLatch) -> Unit,
    ) {
        installOwned(
            native,
            userNotifSupported,
            socketPath,
            filter,
            processWide,
            connectWithRetry,
            sendDescriptor,
            onFilterApplied,
            SeccompInstallationGate.Direct,
            onSocketConnected,
        )
    }

    /** Allocation and coordinator waits stay outside [installationGate]. */
    @io.mazewall.MazewallInternal
    public fun installOwned(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean,
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = defaultConnect,
        sendDescriptor: (Int, Int) -> Boolean = defaultSend,
        onFilterApplied: () -> Unit,
        installationGate: SeccompInstallationGate,
        onSocketConnected: (socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, readyLatch: CountDownLatch) -> Unit,
    ) {
        validateNotVirtual()
        if (!userNotifSupported) {
            throw UnsupportedKernelFeatureException("Seccomp User Notifications are required.")
        }

        // Mandatory for non-privileged seccomp
        native.process
            .prctl(
                io.mazewall.core.PrctlCommand
                .SetNoNewPrivs(true),
            ).getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")

        val arch = Arch.current()

        // Pre-charge BpfProgram classloading to avoid deadlocks under active seccomp filters
        val dummyBpf = if (processWide) {
            BpfProgram.dsl(arch) { allow() }.instructions
        } else {
            // Also build a dummy program unconditionally to pre-charge classloading
            BpfProgram.dsl(arch) { allow() }.instructions
            null
        }

        val prog = BpfNativeCache.getOrCompute(filter.instructions, native)
        val handshake = SeccompInstallHandshake(onFilterApplied)
        val listenerFdVal: AtomicReference<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>?> =
            AtomicReference(null)

        // Coordinator thread spawned BEFORE the seccomp filter is active.
        // This ensures the helper is completely uncontained.
        startCoordinator(
            native,
            socketPath,
            listenerFdVal,
            handshake,
            connectWithRetry,
            sendDescriptor,
            onSocketConnected,
        )

        try {
            handshake.checkActive()

            // Install seccomp user notifier filter (applied to the current tracee thread)
            val r = installationGate.commit {
                native.raw.syscall(
                    arch.seccompSyscallNumber.toLong(),
                    NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                    NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong()),
                    NativeArg.MemoryArg(prog),
                )
            }

            when (r) {
                is LinuxNative.SyscallResult.Success -> handshake.advance(SeccompInstallStage.Applied)
                is LinuxNative.SyscallResult.Error -> {
                    throw IllegalStateException("seccomp syscall failed with errno ${r.errno}")
                }
            }

            listenerFdVal.set(r.getFdOrThrow("install seccomp listener", FileDescriptorRole.SeccompNotif))
            handshake.installed.countDown()

            // Block tracee thread until the coordinator completes descriptor passing and listener startup
            // We wait uninterruptibly to ensure the handshake completes even if the thread is interrupted
            // during executor shutdown. This prevents state desync between the kernel and JVM.
            handshake.await(handshake.proceed)

            // Step 2: Synchronize filter tree process-wide if processWide is true
            // We run this AFTER the coordinator has connected, handshaked, and started the listener.
            // This prevents the coordinator and listener threads from being subject to the seccomp
            // filter during their startup and handshake, avoiding fatal circular deadlocks.
            if (processWide && dummyBpf != null) {
                synchronizeProcessFilter(native, arch, dummyBpf, installationGate)
            }
        } catch (expectedInstallFailure: Exception) {
            cleanupFailedInstallation(handshake, listenerFdVal, native, expectedInstallFailure)
            throw expectedInstallFailure
        } catch (expectedInstallError: Error) {
            cleanupFailedInstallation(handshake, listenerFdVal, native, expectedInstallError)
            throw expectedInstallError
        }
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup preserves the original failure, including fatal errors.
    private fun cleanupFailedInstallation(
        handshake: SeccompInstallHandshake,
        listener: AtomicReference<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>?>,
        native: NativeEngine,
        failure: Throwable,
    ) {
        handshake.fail(failure)
        handshake.installed.countDown()
        try {
            listener.getAndSet(null)?.let { native.fileSystem.close(it).getOrThrow("close failed seccomp listener") }
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        try {
            // Even a coordinator that never ran cannot erase a completed kernel installation.
            handshake.publishApplied()
        } catch (publication: Throwable) {
            if (publication !== failure) failure.addSuppressed(publication)
        }
    }
}
