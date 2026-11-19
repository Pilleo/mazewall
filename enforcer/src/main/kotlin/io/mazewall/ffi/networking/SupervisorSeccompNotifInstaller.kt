package io.mazewall.ffi.networking

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.UnsupportedKernelFeatureException
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.internal.execution.LiveValidationTransport
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.ScopedBpfProgram
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.seccomp.*
import java.lang.foreign.MemorySegment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified orchestrator for installing seccomp notification filters and establishing
 * socket handshakes with the supervisor/profiler daemon.
 *
 * This implementation replicates the coordinator pattern from the original profiler
 * to prevent deadlocks when supervising critical JVM operations (like open/openat).
 */
internal object SupervisorSeccompNotifInstaller {
    private val defaultConnect: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = { SupervisorSocketUtils.connectDescriptorWithRetry(it) }
    private val defaultSend: (Int, Int) -> Boolean = { socket, listener -> SupervisorSocketUtils.sendDescriptor(socket, listener) }

    private fun synchronizeProcessFilter(
        native: NativeEngine,
        arch: Arch,
        dummyBpf: BpfProgram<BpfStatus.Verified>,
        installationGate: SeccompInstallationGate,
    ) {
        val tsyncRes = ScopedBpfProgram.withProgram(dummyBpf, native) { dummyProg ->
            installationGate.commit {
                native.raw.syscall(
                    arch.seccompSyscallNumber.toLong(),
                    NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                    NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_TSYNC.toLong()),
                    NativeArg.MemoryArg(dummyProg),
                )
            }
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

    /** Every ownership boundary must retain even a direct Throwable before reporting failure. */
    private class CoordinatorCleanup(
        initial: Throwable? = null,
    ) {
        var failure: Throwable? = initial
            private set
        private var fatal: Error? = null

        @Suppress("TooGenericExceptionCaught") // Irreversible installation and owned FDs require cleanup for every throwable.
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                failure = CleanupFailures.merge(failure, interrupted)
            } catch (problem: Error) {
                if (fatal == null) fatal = problem
                failure = CleanupFailures.merge(failure, problem)
            } catch (problem: Throwable) {
                failure = CleanupFailures.merge(failure, problem)
            }
        }

        fun report(handshake: SeccompInstallHandshake) {
            failure?.let(handshake::fail)
            if (fatal != null) {
                // Preserve any earlier absorbing failure alongside a later fatal cleanup.
                attempt(handshake::checkActive)
                throw checkNotNull(fatal)
            }
        }

        fun rethrow() {
            failure?.let { throw it }
        }
    }

    private class CoordinatorHooks(
        val connect: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        val send: (Int, Int) -> Boolean,
        val onConnected: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, CountDownLatch) -> Unit,
    )

    private sealed interface ValidationMode {
        data object Legacy : ValidationMode

        class Live(
            val receipt: LiveValidationTransport,
        ) : ValidationMode
    }

    /** The caller retains the listener and socket; the same transfer is used by the coordinator. */
    internal fun transferLiveListener(
        native: NativeEngine,
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        listener: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
        receipt: LiveValidationTransport,
        handshake: SeccompInstallHandshake,
    ): Boolean {
        handshake.checkActive()
        receipt.send(socket, ConfinedDeadline.mint(native) { TimeUnit.MILLISECONDS.toNanos(handshake.remainingMillis().toLong()) })
        handshake.checkActive()
        receipt.requireCurrent()
        handshake.checkActive()
        return SeccompInstallTransport(native, handshake).send(socket, listener.value)
    }

    private fun startCoordinator(
        native: NativeEngine,
        socketPath: String,
        listenerFdVal: AtomicReference<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>?>,
        handshake: SeccompInstallHandshake,
        mode: ValidationMode,
        connectWithRetry: (String) -> FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        sendDescriptor: (Int, Int) -> Boolean,
        onSocketConnected: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, CountDownLatch) -> Unit,
    ): Thread =
        Thread {
            val coordinator = CoordinatorCleanup()
            coordinator.attempt {
                val transport = SeccompInstallTransport(native, handshake)
                handshake.await(handshake.installed)
                handshake.publishApplied()
                handshake.checkActive()

                val socketFd = if (connectWithRetry === defaultConnect) transport.connect(socketPath) else connectWithRetry(socketPath)
                var socketTransferred = false
                val connection = CoordinatorCleanup()
                connection.attempt {
                    handshake.checkActive()
                    // Claim the listener only once the coordinator is ready to use it.
                    val fdRef = checkNotNull(listenerFdVal.getAndSet(null)) { "Seccomp listener handoff cancelled" }
                    val transfer = CoordinatorCleanup()
                    transfer.attempt {
                        val sent = when (mode) {
                            ValidationMode.Legacy -> if (sendDescriptor === defaultSend) transport.send(socketFd, fdRef.value) else sendDescriptor(socketFd.value, fdRef.value)
                            is ValidationMode.Live -> transferLiveListener(native, socketFd, fdRef, mode.receipt, handshake)
                        }
                        check(sent) { "Failed to send seccomp listener FD to daemon" }
                    }
                    transfer.attempt { native.fileSystem.close(fdRef).getOrThrow("close transferred seccomp listener") }
                    transfer.rethrow()
                    handshake.advance(SeccompInstallStage.Transferred)
                    handshake.checkActive()

                    val readyLatch = CountDownLatch(1)
                    // The callback owns the socket on entry, including startup failure.
                    socketTransferred = true
                    onSocketConnected(socketFd, readyLatch)
                    handshake.await(readyLatch)
                }
                if (!socketTransferred) {
                    connection.attempt { native.fileSystem.close(socketFd).getOrThrow("close failed coordinator socket") }
                }
                connection.rethrow()
                handshake.advance(SeccompInstallStage.Ready)
            }
            coordinator.report(handshake)
        }.apply {
            isDaemon = true
            name = "seccomp-install-coordinator"
            start()
        }

    @Deprecated("Raw descriptor callbacks are disabled; use installOwned")
    // SUPPRESSION-RATIONALE: The binary migration overload retains its parameters but rejects before invoking callbacks or installing irreversible filters.
    @Suppress("UnusedParameter")
    public fun install(
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean = false,
        connectWithRetry: (String) -> Int = { error("Raw Int connect is disabled; use installOwned") },
        sendDescriptor: (Int, Int) -> Boolean = { sockFd, fd -> SupervisorSocketUtils.sendDescriptor(sockFd, fd) },
        onFilterApplied: () -> Unit = {},
        onSocketConnected: (socketFd: Int, readyLatch: CountDownLatch) -> Unit,
    ) {
        error("Raw descriptor callbacks are disabled; use installOwned")
    }

    @Deprecated("Raw descriptor callbacks are disabled; use installOwned")
    // SUPPRESSION-RATIONALE: The binary migration overload retains its parameters but rejects before invoking callbacks or installing irreversible filters.
    @Suppress("UnusedParameter")
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
    // SUPPRESSION-RATIONALE: The binary migration overload retains its parameters but rejects before invoking callbacks or installing irreversible filters.
    @Suppress("UnusedParameter")
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
    @io.mazewall.platform.lifecycle.MazewallInternal
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
    @io.mazewall.platform.lifecycle.MazewallInternal
    // SUPPRESSION-RATIONALE: Platform support, irreversible installation and bounded handshake failures retain distinct throw sites and explicit partial-installation evidence.
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
        installOwnedInternal(
            native,
            userNotifSupported,
            socketPath,
            filter,
            processWide,
            CoordinatorHooks(connectWithRetry, sendDescriptor, onSocketConnected),
            onFilterApplied,
            installationGate,
            ValidationMode.Legacy,
        )
    }

    /** Dormant Live route: the receipt is minted by the original domain, never by wire claims. */
    internal fun installLiveOwned(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        receipt: LiveValidationTransport,
        onFilterApplied: () -> Unit,
        installationGate: SeccompInstallationGate,
        onSocketConnected: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>, CountDownLatch) -> Unit,
    ) {
        installOwnedInternal(
            native,
            userNotifSupported,
            socketPath,
            filter,
            false,
            CoordinatorHooks(defaultConnect, defaultSend, onSocketConnected),
            onFilterApplied,
            installationGate,
            ValidationMode.Live(receipt),
        )
    }

    private fun installOwnedInternal(
        native: NativeEngine,
        userNotifSupported: Boolean,
        socketPath: String,
        filter: BpfProgram<BpfStatus.Verified>,
        processWide: Boolean,
        hooks: CoordinatorHooks,
        onFilterApplied: () -> Unit,
        installationGate: SeccompInstallationGate,
        mode: ValidationMode,
    ) {
        validateNotVirtual()
        requireUserNotif(userNotifSupported)

        // Mandatory for non-privileged seccomp
        native.process
            .prctl(
                io.mazewall.platform.syscall.PrctlCommand
                .SetNoNewPrivs(true),
            ).getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")

        val arch = Arch.current()

        // Pre-charge BpfProgram classloading to avoid deadlocks under active seccomp filters
        val dummyBpf = BpfStaticVerifier.verify(BpfProgram.dsl(arch) { allow() }).takeIf { processWide }

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
            mode,
            hooks.connect,
            hooks.send,
            hooks.onConnected,
        )

        val installation = CoordinatorCleanup()
        installation.attempt {
            handshake.checkActive()

            // Install seccomp user notifier filter (applied to the current tracee thread)
            val r = ScopedBpfProgram.withProgram(filter, native) { prog ->
                installationGate.commit {
                    native.raw.syscall(
                        arch.seccompSyscallNumber.toLong(),
                        NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                        NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER.toLong()),
                        NativeArg.MemoryArg(prog),
                    )
                }
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
        }
        installation.failure?.let { throw cleanupFailedInstallation(handshake, listenerFdVal, native, it) }
    }

    private fun requireUserNotif(supported: Boolean) {
        if (!supported) throw UnsupportedKernelFeatureException("Seccomp User Notifications are required.")
    }

    private fun cleanupFailedInstallation(
        handshake: SeccompInstallHandshake,
        listener: AtomicReference<FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>?>,
        native: NativeEngine,
        failure: Throwable,
    ): Throwable {
        handshake.fail(failure)
        handshake.installed.countDown()
        val cleanup = CoordinatorCleanup(failure)
        cleanup.attempt { listener.getAndSet(null)?.let { native.fileSystem.close(it).getOrThrow("close failed seccomp listener") } }
        // Even a coordinator that never ran cannot erase a completed kernel installation.
        cleanup.attempt(handshake::publishApplied)
        return checkNotNull(cleanup.failure)
    }
}
