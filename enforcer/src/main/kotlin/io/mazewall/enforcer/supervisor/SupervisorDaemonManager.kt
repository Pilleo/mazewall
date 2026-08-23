package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.seccomp.SeccompInstallationState
import io.mazewall.core.JavaAgentSelection
import io.mazewall.core.JvmChildProcess
import io.mazewall.core.JvmChildSpec
import io.mazewall.core.PrivateUnixEndpoint
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.writeByte
import io.mazewall.getFdOrThrow
import java.io.IOException
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Context for a running Supervisor Daemon.
 */
public data class SupervisorContext(
    val socketPath: String,
    val socketDir: Path,
    val daemonProcess: Process,
    val shutdownHook: Thread,
)

/**
 * Manages the lifecycle of the shared Supervisor Daemon.
 */
public class SupervisorDaemonManager(
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = RealSocketManager,
    private val processLauncher: ProcessLauncher = RealProcessLauncher
) {
    private val logger = Logger.getLogger(SupervisorDaemonManager::class.java.name)
    private val daemonLock = Any()
    private var sharedDaemonContext: SupervisorContext? = null

    // Visible for testing: allows test suites to intercept unexpected exit without terminating the JVM
    internal var onUnexpectedExit: (exitCode: Int) -> Unit = {
        Runtime.getRuntime().halt(1)
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
    public fun getOrSpawnSharedDaemon(): SupervisorContext {
        synchronized(daemonLock) {
            val existing = sharedDaemonContext
            if (existing != null && existing.daemonProcess.isAlive) {
                engine.process.prctl(
                    io.mazewall.core.PrctlCommand.SetPtracer(existing.daemonProcess.pid())
                )
                return existing
            }
            val newContext = spawnDaemon()
            sharedDaemonContext = newContext
            return newContext
        }
    }

    /**
     * Stops the shared daemon and cleans up resources.
     */
    public fun stop() {
        synchronized(daemonLock) {
            sharedDaemonContext?.let {
                cleanupDaemon(it)
                sharedDaemonContext = null
            }
        }
    }

    private fun cleanupDaemon(context: SupervisorContext) {
        try {
            processLauncher.removeShutdownHook(context.shutdownHook)
        } catch (ignored: IllegalStateException) {
            // Shutdown already in progress - ignore
        } catch (e: SecurityException) {
            logger.log(java.util.logging.Level.WARNING, "Failed to remove shutdown hook", e)
        }
        triggerDaemonShutdown(context.socketPath, context.daemonProcess)
        context.daemonProcess.destroyForcibly()
        try {
            processLauncher.deleteIfExists(context.socketDir.resolve("supervisor.sock"))
            processLauncher.deleteIfExists(context.socketDir)
        } catch (e: IOException) {
            logger.log(
                java.util.logging.Level.WARNING,
                "Failed to delete secure socket directory at ${context.socketDir}",
                e,
            )
        }
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
        val endpoint =
            PrivateUnixEndpoint.create(processLauncher, "mazewall-supervisor-", "supervisor.sock")
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

        val daemonProcess = JvmChildProcess.start(processLauncher, spec)
        val daemonPid = daemonProcess.pid()

        val prctlRes = engine.process.prctl(
            io.mazewall.core.PrctlCommand.SetPtracer(daemonPid)
        )
        if (prctlRes is io.mazewall.LinuxNative.SyscallResult.Error) {
            logger.warning("prctl(PR_SET_PTRACER) failed with errno ${prctlRes.errno}. The daemon may not be able to read process memory if Yama ptrace_scope is restrictive.")
        }

        val shutdownHook = Thread {
            synchronized(daemonLock) {
                if (sharedDaemonContext?.daemonProcess == daemonProcess) {
                    sharedDaemonContext = null
                }
            }
            daemonProcess.destroyForcibly()
        }
        processLauncher.addShutdownHook(shutdownHook)

        val context = SupervisorContext(socketPath, socketDir, daemonProcess, shutdownHook)
        sharedDaemonContext = context

        val pump =
            JvmChildProcess.startStdoutPump(
                process = daemonProcess,
                readySentinel = SupervisorDaemon.DAEMON_READY_SENTINEL,
                onLine = { line ->
                    daemonLogLines.add(line)
                    System.err.println("[SUPERVISOR-DAEMON] $line")
                    System.err.flush()
                },
                threadName = "supervisor-daemon-output",
                onStreamClosed = {
                    synchronized(daemonLock) {
                        val currentContext = sharedDaemonContext
                        if (currentContext != null && currentContext.daemonProcess == daemonProcess) {
                            if (!daemonProcess.isAlive) {
                                val exitCode = try {
                                    daemonProcess.exitValue()
                                } catch (_: Exception) {
                                    -1
                                }
                                logger.severe("SupervisorDaemon (PID=${daemonProcess.pid()}) exited unexpectedly with exit code $exitCode!")
                                logger.severe("Last daemon log lines:")
                                daemonLogLines.forEach { line ->
                                    logger.severe("[SUPERVISOR-DAEMON-CRASH-LOG] $line")
                                }
                                onUnexpectedExit(exitCode)
                            }
                        }
                    }
                },
            )

        val ready = JvmChildProcess.awaitReady(pump, LATCH_TIMEOUT_SECONDS)

        if (!ready) {
            val alive = daemonProcess.isAlive
            val exitCode = if (!alive) daemonProcess.exitValue() else -1
            if (alive) daemonProcess.destroyForcibly()

            throw IllegalStateException(
                "SupervisorDaemon failed to signal readiness within 30s (exitCode=$exitCode).",
            )
        }

        return context
    }

    /**
     * Best-effort graceful shutdown: send the shutdown command, then wait (bounded) for the daemon
     * to observe it and exit on its own before the caller escalates to destroyForcibly
     * (issue-20260823-172000). Sleep-based waiting is replaced by a liveness poll so shutdown
     * success is observable and fast daemons are not delayed by a fixed 100ms.
     */
    private fun triggerDaemonShutdown(socketPath: String, process: Process) {
        try {
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val fd = socketManager.connect(socketPath)
                try {
                    val cmd = arena.allocate(1L)
                    cmd.writeByte(0L, SHUTDOWN_COMMAND_BYTE)
                    while (true) {
                        val writeRes = engine.memory.write(fd, cmd, 1)
                        if (writeRes is io.mazewall.LinuxNative.SyscallResult.Error && writeRes.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                            continue
                        }
                        break
                    }
                } finally {
                    socketManager.close(fd)
                }
            }
        } catch (e: Exception) {
            logger.log(java.util.logging.Level.FINE, "Daemon shutdown command could not be delivered", e)
            return // destroyForcibly() by the caller is the authoritative escalation.
        }
        // Bounded liveness poll: exit as soon as the daemon acknowledges by dying.
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
        try {
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
