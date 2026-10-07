package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.memory.writeByte
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
)

/**
 * Manages the lifecycle of the shared Supervisor Daemon.
 */
internal class SupervisorDaemonManager(
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = RealSocketManager,
    private val processLauncher: ProcessLauncher = RealProcessLauncher,
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

    /**
     * Returns the existing shared daemon context or spawns a new one.
     */
    public fun getOrSpawnSharedDaemon(callerNative: NativeEngine = engine): SupervisorContext {
        synchronized(daemonLock) {
            when (val handle = daemonHandle) {
                is DaemonHandle.Running -> {
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
        pendingStartup = null
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
        val startup = ChildStartupTransaction(processLauncher, "mazewall-supervisor-", "supervisor.sock")
        pendingStartup = startup
        val context = startup.runStartup(::startDaemon)
        pendingStartup = null
        return context
    }

    private fun startDaemon(startup: ChildStartupTransaction): SupervisorContext {
        val endpoint = startup.endpoint
        val socketDir = endpoint.dir
        val socketPath = endpoint.path

        val spec =
            JvmChildSpec(
                mainClass = SupervisorDaemon::class.java.name,
                mainArgs = listOf(socketPath),
                maxHeap = "64m",
                javaAgents = JavaAgentSelection.All,
            )
        val pbArgs = JvmChildProcess.commandLine(spec)
        logger.info("Spawning SupervisorDaemon: ${pbArgs.joinToString(" ")}")

        val daemonProcess = startup.start(spec)

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
        val context = SupervisorContext(socketPath, socketDir, daemonProcess, startup.shutdownHook)
        val running = DaemonHandle.Running(context)
        val resources = startup.commit()
        ownedResources = resources
        daemonHandle = running
        if (!daemonProcess.isAlive) markDefunct(context, daemonProcess.exitValue())
        return context
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
