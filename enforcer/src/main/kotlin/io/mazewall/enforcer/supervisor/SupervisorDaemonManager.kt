package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.seccomp.SeccompInstallationState
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.ffi.memory.writeByte
import io.mazewall.getFdOrThrow
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
        triggerDaemonShutdown(context.socketPath)
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
        val daemonClassName = SupervisorDaemon::class.java.name

        val perms = PosixFilePermissions.fromString("rwx------")
        var socketDir = processLauncher.createTempDirectory("mazewall-supervisor-", PosixFilePermissions.asFileAttribute(perms))
        var socketPath = socketDir.resolve("supervisor.sock").toAbsolutePath().toString()

        if (socketPath.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size >= 108) {
            try {
                processLauncher.deleteIfExists(socketDir.resolve("supervisor.sock"))
                processLauncher.deleteIfExists(socketDir)
            } catch (ignored: Exception) {}

            val tmpDir = java.nio.file.Path.of("/tmp")
            socketDir = processLauncher.createTempDirectory(tmpDir, "mazewall-supervisor-", PosixFilePermissions.asFileAttribute(perms))
            socketPath = socketDir.resolve("supervisor.sock").toAbsolutePath().toString()

            require(socketPath.toByteArray(java.nio.charset.StandardCharsets.UTF_8).size < 108) {
                "Failed to generate a safe UNIX socket path (exceeds 107 bytes): $socketPath"
            }
        }

        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")

        val jvmArgs = java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
        val javaAgents = jvmArgs.filter { it.startsWith("-javaagent:") }

        val pbArgs = mutableListOf<String>()
        pbArgs.add(javaBin)
        pbArgs.add("--enable-native-access=ALL-UNNAMED")
        pbArgs.add("-Xmx64m")
        pbArgs.addAll(javaAgents)
        pbArgs.add("-cp")
        pbArgs.add(classpath)
        pbArgs.add(daemonClassName)
        pbArgs.add(socketPath)

        logger.info("Spawning SupervisorDaemon: ${pbArgs.joinToString(" ")}")

        val daemonProcess = processLauncher.startProcess(pbArgs)
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

        val readyLatch = java.util.concurrent.CountDownLatch(1)

        Thread {
            try {
                val reader = daemonProcess.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains(SupervisorDaemon.DAEMON_READY_SENTINEL)) {
                        readyLatch.countDown()
                    }
                    daemonLogLines.add(line)
                    System.err.println("[SUPERVISOR-DAEMON] $line")
                    System.err.flush()
                }
            } catch (ignored: IOException) {
                // Stopped
            } catch (t: Throwable) {
                logger.log(java.util.logging.Level.SEVERE, "Exception inside supervisor-daemon-output thread", t)
            } finally {
                synchronized(daemonLock) {
                    val currentContext = sharedDaemonContext
                    if (currentContext != null && currentContext.daemonProcess == daemonProcess) {
                        if (!daemonProcess.isAlive) {
                            val exitCode = try { daemonProcess.exitValue() } catch (e: Exception) { -1 }
                            logger.severe("SupervisorDaemon (PID=${daemonProcess.pid()}) exited unexpectedly with exit code $exitCode!")
                            logger.severe("Last daemon log lines:")
                            daemonLogLines.forEach { line ->
                                logger.severe("[SUPERVISOR-DAEMON-CRASH-LOG] $line")
                            }
                            onUnexpectedExit(exitCode)
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "supervisor-daemon-output"
        }.start()

        // Wait for sentinel
        val ready = readyLatch.await(LATCH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

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

    private fun triggerDaemonShutdown(socketPath: String) {
        try {
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val fd = socketManager.connect(socketPath)
                try {
                    val cmd = arena.allocate(1L)
                    cmd.writeByte(0L, SHUTDOWN_COMMAND_BYTE)
                    var writeRes: io.mazewall.LinuxNative.SyscallResult<Long, *>
                    while (true) {
                        writeRes = engine.memory.write(fd, cmd, 1)
                        if (writeRes is io.mazewall.LinuxNative.SyscallResult.Error && writeRes.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                            continue
                        }
                        break
                    }
                    try {
                        Thread.sleep(SHUTDOWN_WAIT_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                } finally {
                    socketManager.close(fd)
                }
            }
        } catch (ignored: Exception) {
            // Ignore
        }
    }
}
