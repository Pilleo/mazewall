package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.diagnostics.requirePlatformThread
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.writeByte
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.process.ChildProcessResources
import io.mazewall.platform.process.ChildStartupTransaction
import io.mazewall.platform.process.DaemonPtracer
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import io.mazewall.seccomp.SeccompInstallationState
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Context for a running Supervisor Daemon.
 */
internal data class SupervisorContext(
    val socketPath: String,
    val socketDir: Path,
    val daemonProcess: Process,
    val shutdownHook: Thread,
    val confinedBootstrap: SocketHelperProcess.ConfinedStartup? = null,
    val confinedHelper: HelperSecurityObservation.Observation? = null,
)

/**
 * Manages the lifecycle of the shared Supervisor Daemon.
 */
internal class SupervisorDaemonManager(
    private val engine: NativeEngine = LinuxNative.snapshot(),
    private val socketManager: SocketManager = RealSocketManager.capture(engine),
    private val processLauncher: ProcessLauncher = RealProcessLauncher,
    private val observations: HelperSecurityObservation = HelperSecurityObservation(),
) {
    private val logger = Logger.getLogger(SupervisorDaemonManager::class.java.name)
    private val daemonLock = Any()

    private sealed interface DaemonHandle {
        data object NotStarted : DaemonHandle

        data class Running(
            val context: SupervisorContext,
        ) : DaemonHandle

        data class Defunct(
            val context: SupervisorContext,
        ) : DaemonHandle
    }

    private val handleRef = java.util.concurrent.atomic
        .AtomicReference<DaemonHandle>(DaemonHandle.NotStarted)
    private var daemonHandle: DaemonHandle
        get() = handleRef.get()
        set(value) {
            handleRef.set(value)
        }

    @Volatile private var ownedResources: ChildProcessResources? = null
    private var pendingStartup: ChildStartupTransaction? = null
    private var confinedOriginalProcess: Process? = null
    private var confinedCommitted = false
    private var confinedStartupFailure: Throwable? = null
    private var confinedPtracerFailure: Throwable? = null
    private var confinedMetadataFailure: Throwable? = null
    private var provisioning = PoolProvisioning.Legacy
    private var confinedAttempted = false

    // Visible for testing: allows test suites to intercept unexpected exit without terminating the JVM
    internal var onUnexpectedExit: (exitCode: Int) -> Unit = { exitCode ->
        // The halt itself is non-negotiable: stranded USER_NOTIF waiters cannot be resumed.
        Runtime.getRuntime().halt(1)
    }

    private fun daemonProcessPid(): Long =
        synchronized(daemonLock) {
            when (val handle = daemonHandle) {
                DaemonHandle.NotStarted -> -1L
                is DaemonHandle.Running -> handle.context.daemonProcess.pid()
                is DaemonHandle.Defunct -> handle.context.daemonProcess.pid()
            }
        }

    public companion object {
        private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte() // 'S'
        private const val SHUTDOWN_WAIT_MS = 100L
        private const val LATCH_TIMEOUT_SECONDS = 30L
        private val INSTANCE_DEFAULT = SupervisorDaemonManager()

        @JvmStatic
        public fun getInstance(): SupervisorDaemonManager = INSTANCE_DEFAULT
    }

    public val daemonLogLines: java.util.concurrent.ConcurrentLinkedQueue<String> = java.util.concurrent.ConcurrentLinkedQueue<String>()

    internal fun prepareConfinedReadBootstrap(): SupervisorContext {
        requirePlatformThread()
        synchronized(daemonLock) {
            refuseSpawnIfParentIsFiltered()
            return getOrSpawn(PoolProvisioning.PrestartedConfined, engine)
        }
    }

    /**
     * Returns the existing shared daemon context or spawns a new one.
     */
    public fun getOrSpawnSharedDaemon(callerNative: NativeEngine = engine): SupervisorContext = getOrSpawn(PoolProvisioning.Legacy, callerNative)

    private fun getOrSpawn(
        requested: PoolProvisioning,
        callerNative: NativeEngine,
    ): SupervisorContext {
        synchronized(daemonLock) {
            check(!confinedAttempted || requested == PoolProvisioning.PrestartedConfined) { "Confined daemon cannot switch to legacy mode" }
            when (val handle = daemonHandle) {
                is DaemonHandle.Running -> {
                    check(requested == provisioning) { "Supervisor daemon mode differs from requested bootstrap" }
                    if (handle.context.daemonProcess.isAlive) {
                        DaemonPtracer.authorize(callerNative, handle.context.daemonProcess)
                        return handle.context
                    }
                    markDefunct(handle.context, handle.context.daemonProcess.exitValue())
                    cleanupDaemon(handle.context)
                }
                is DaemonHandle.Defunct -> cleanupDaemon(handle.context)
                DaemonHandle.NotStarted -> retirePendingResources()
            }
            if (requested == PoolProvisioning.PrestartedConfined) {
                check(!confinedAttempted) { "Confined daemon startup is spent; no replacement is permitted" }
                confinedAttempted = true
            }
            provisioning = requested
            return spawnDaemon()
        }
    }

    /**
     * Stops the shared daemon and cleans up resources.
     */
    public fun stop() {
        synchronized(daemonLock) {
            when (val handle = daemonHandle) {
                is DaemonHandle.Running -> cleanupDaemon(handle.context)
                is DaemonHandle.Defunct -> cleanupDaemon(handle.context)
                DaemonHandle.NotStarted -> retirePendingResources()
            }
            daemonHandle = DaemonHandle.NotStarted
        }
    }

    /** Marks the current daemon dead exactly once, emits diagnostics, then applies the fail-closed policy. */
    private fun markDefunct(
        context: SupervisorContext,
        exitCode: Int,
    ) {
        val current = daemonHandle
        if (current !is DaemonHandle.Running || current.context.daemonProcess != context.daemonProcess) return
        if (!handleRef.compareAndSet(current, DaemonHandle.Defunct(context))) return
        logger.severe("SupervisorDaemon (PID=${context.daemonProcess.pid()}) exited unexpectedly with exit code $exitCode!")
        logger.severe("Last daemon log lines:")
        daemonLogLines.forEach { line -> logger.severe("[SUPERVISOR-DAEMON-CRASH-LOG] $line") }
        io.mazewall.enforcer.diagnostics.MazewallEvents.emit(
            io.mazewall.enforcer.diagnostics.MazewallEvents.DaemonExited(
                pid = context.daemonProcess.pid(),
                exitCode = exitCode,
                lastLogLines = daemonLogLines.toList(),
            ),
        )
        onUnexpectedExit(exitCode)
    }

    private fun cleanupDaemon(context: SupervisorContext) {
        DaemonPtracer.retire(engine, context.daemonProcess)
        val resources = ownedResources ?: return
        check(resources.process === context.daemonProcess) { "Daemon resource ownership mismatch" }
        daemonHandle = DaemonHandle.NotStarted
        resources.use {
            if (context.daemonProcess.isAlive) triggerDaemonShutdown(context.socketPath, context.daemonProcess)
        }
        ownedResources = null
    }

    private fun retirePendingResources() {
        pendingStartup?.close()
        if (provisioning != PoolProvisioning.PrestartedConfined) pendingStartup = null
        ownedResources?.close()
        ownedResources = null
    }

    private fun refuseSpawnIfParentIsFiltered() {
        // PR_GET_SECCOMP==2 is also the outer OCI/podman profile. The daemon is meant
        // to run inside that profile. Only refuse after *this JVM* applied a mazewall filter
        // (the child would inherit it and could not operate).
        val merged = ContainmentStateRegistry.resolveCurrentState()
        val mazewallApplied =
            merged.filterDepth > 0 ||
                merged.engineState is SeccompInstallationState.Verified ||
                merged.engineState is SeccompInstallationState.SystemCallApplied ||
                merged.engineState is SeccompInstallationState.FallbackPrctlApplied
        check(!mazewallApplied) {
            "Cannot spawn SupervisorDaemon after mazewall seccomp is installed on this JVM " +
                "(filterDepth=${merged.filterDepth}). Spawn the daemon before installOnProcess, " +
                "or run this test in an isolated JVM."
        }
    }

    private fun spawnDaemon(): SupervisorContext {
        refuseSpawnIfParentIsFiltered()
        val startup = when (provisioning) {
            PoolProvisioning.Legacy -> ChildStartupTransaction(processLauncher, "mazewall-supervisor-", "supervisor.sock")
            PoolProvisioning.PrestartedConfined -> ChildStartupTransaction.confined(processLauncher, "mazewall-supervisor-", "supervisor.sock")
        }
        pendingStartup = startup
        val context = when (provisioning) {
            PoolProvisioning.Legacy -> startup.runStartup(::startDaemon)
            PoolProvisioning.PrestartedConfined -> runConfinedStartup(startup)
        }
        pendingStartup = null
        return context
    }

    @Suppress("TooGenericExceptionCaught") // Retain the original rollback result before independent bookkeeping cleanup.
    private fun runConfinedStartup(startup: ChildStartupTransaction): SupervisorContext =
        try {
            startup.runStartup(::startDaemon)
        } catch (failure: Throwable) {
            confinedStartupFailure = failure
            val process = confinedOriginalProcess
            if (process != null && !confinedCommitted) retireFailedStartupPtracer(process, failure) else throw failure
        }

    @Suppress("TooGenericExceptionCaught") // Preserve arbitrary bookkeeping and metadata failures after child rollback.
    private fun retireFailedStartupPtracer(process: Process, startupFailure: Throwable): Nothing {
        val bookkeepingFailure = try {
            DaemonPtracer.retire(engine, process)
            null
        } catch (failure: InterruptedException) {
            confinedPtracerFailure = failure
            Thread.currentThread().interrupt()
            failure
        } catch (failure: Throwable) {
            confinedPtracerFailure = failure
            failure
        }
        if (bookkeepingFailure == null) throw startupFailure
        val originalFatal = (startupFailure as? Error) ?: (bookkeepingFailure as? Error)
        val selected = try {
            CleanupFailures.merge(startupFailure, bookkeepingFailure)
        } catch (metadataFailure: Throwable) {
            confinedMetadataFailure = metadataFailure
            originalFatal ?: (metadataFailure as? Error) ?: startupFailure
        }
        throw selected
    }

    private fun startDaemon(startup: ChildStartupTransaction): SupervisorContext {
        val endpoint = startup.endpoint
        val socketDir = endpoint.dir
        val socketPath = endpoint.path

        val spec =
            JvmChildSpec(
                mainClass = SupervisorDaemon::class.java.name,
                mainArgs = when (provisioning) {
                    PoolProvisioning.Legacy -> listOf(socketPath)
                    PoolProvisioning.PrestartedConfined -> listOf(socketPath, "--confined-read-v1")
                },
                maxHeap = "64m",
                javaAgents = JavaAgentSelection.All,
                extraJvmArgs = SupervisorPrewarmBudget.childArguments(System.getProperty(SupervisorPrewarmBudget.PROPERTY)),
            )
        val pbArgs = JvmChildProcess.commandLine(spec)
        logger.info("Spawning SupervisorDaemon: ${pbArgs.joinToString(" ")}")

        val parent = when (provisioning) {
            PoolProvisioning.Legacy -> null
            PoolProvisioning.PrestartedConfined -> {
                val pid = Pid(ProcessHandle.current().pid().toInt())
                val bootstrap = observeConfined(pid, Tid(pid.value))
                observeConfined(pid, engine.process.gettid()).also {
                    check(it.identity == bootstrap.identity) { "Daemon spawning process changed" }
                    it.requireLineage(bootstrap, HelperSecurityObservation.Lineage.Bootstrap)
                }
            }
        }
        val daemonProcess = startup.start(spec)
        if (provisioning == PoolProvisioning.PrestartedConfined) confinedOriginalProcess = daemonProcess
        val confinedBootstrap = parent?.let {
            val pid = Pid(Math.toIntExact(daemonProcess.pid()))
            SocketHelperProcess.ConfinedStartup(it, observeConfined(pid, Tid(pid.value))).also(SocketHelperProcess.ConfinedStartup::requireCommon)
        }

        DaemonPtracer.authorize(engine, daemonProcess)

        startup.startOutputPump(
                sentinel = SupervisorDaemon.DAEMON_READY_SENTINEL,
                onLine = { line ->
                    daemonLogLines.add(line)
                    System.err.println("[SUPERVISOR-DAEMON] $line")
                    System.err.flush()
                },
                threadName = "supervisor-daemon-output",
                onStreamClosed = { observeDaemonExit(daemonProcess) },
            )

        startup.registerShutdownHook()
        startup.awaitReady(LATCH_TIMEOUT_SECONDS)
        confinedBootstrap?.let {
            val pid = it.child.identity.pid
            val current = observeConfined(pid, Tid(pid.value))
            check(current.identity == it.child.identity) { "Daemon incarnation changed before readiness" }
            current.requireLineage(it.child, HelperSecurityObservation.Lineage.Bootstrap)
        }
        val confinedHelper = confinedBootstrap?.let { observations.observeOnlyChild(it.child) }
        val context = SupervisorContext(socketPath, socketDir, daemonProcess, startup.shutdownHook, confinedBootstrap, confinedHelper)
        val running = DaemonHandle.Running(context)
        val resources = startup.commit()
        if (provisioning == PoolProvisioning.PrestartedConfined) confinedCommitted = true
        ownedResources = resources
        daemonHandle = running
        if (!daemonProcess.isAlive) markDefunct(context, daemonProcess.exitValue())
        return context
    }

    @Suppress("TooGenericExceptionCaught") // Acquired-child observation failures retain typed errno and their original cause.
    private fun observeConfined(
        pid: Pid,
        tid: Tid,
    ): HelperSecurityObservation.Observation =
        try {
            observations.observeAncestry(pid, tid, HelperSecurityObservation.Lineage.Bootstrap)
        } catch (observationFailure: Exception) {
            throw ConfinedHelperRejection.classify(observationFailure, ConfinedRejectReason.Ancestry)
        }

    /** EOF can precede process exit; the owned output thread remains the lifetime observer. */
    private fun observeDaemonExit(process: Process) {
        val exitCode = try {
            process.waitFor()
        } catch (expectedInterruption: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        val current = daemonHandle
        if (current is DaemonHandle.Running &&
            current.context.daemonProcess === process &&
            ownedResources?.isClosed != true &&
            !process.isAlive
        ) {
            markDefunct(current.context, exitCode)
        }
    }

    /**
     * Best-effort graceful shutdown: send the shutdown command, then wait (bounded) for the daemon
     * to observe it and exit on its own before the caller escalates to destroyForcibly
     * (issue-20260823-172000). Sleep-based waiting is replaced by a liveness poll so shutdown
     * success is observable and fast daemons are not delayed by a fixed 100ms.
     */
    private fun triggerDaemonShutdown(
        socketPath: String,
        process: Process,
    ) {
        try {
            sendShutdownCommand(socketPath)
        } catch (expectedTransportFailure: Exception) {
            logger.log(java.util.logging.Level.FINE, "Daemon shutdown command could not be delivered", expectedTransportFailure)
            return // destroyForcibly() by the caller is the authoritative escalation.
        }
        awaitDaemonExit(process)
    }

    private fun sendShutdownCommand(socketPath: String) {
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val fd = socketManager.connect(socketPath)
            try {
                val command = arena.allocate(1L)
                command.writeByte(0L, SHUTDOWN_COMMAND_BYTE)
                writeCommandUntilNotInterrupted(fd, command)
            } finally {
                socketManager.close(fd)
            }
        }
    }

    private fun writeCommandUntilNotInterrupted(
        fd: io.mazewall.platform.fd.FileDescriptor<
            io.mazewall.platform.fd.FileDescriptorRole.UnixSocket,
            io.mazewall.platform.fd.FdState.Open,
            io.mazewall.platform.fd.FdOwnership.Owned,
        >,
        command: io.mazewall.ffi.memory.ManagedSegment,
    ) {
        do {
            val result = engine.memory.write(fd, command, 1)
        } while (result is LinuxNative.SyscallResult.Error && result.errno == io.mazewall.ffi.NativeConstants.EINTR)
    }

    private fun awaitDaemonExit(process: Process) {
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
        try {
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
