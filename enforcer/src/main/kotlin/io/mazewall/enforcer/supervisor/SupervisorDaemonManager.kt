package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.onFailure
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.getFdOrThrow
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
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
                engine.withTransaction {
                    engine.process.prctl(
                        io.mazewall.core.PrctlCommand.SetPtracer(existing.daemonProcess.pid())
                    ).onFailure { errno, _ ->
                        logger.warning("prctl(PR_SET_PTRACER) failed for existing daemon: errno=$errno")
                    }
                    Unit
                }
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

    private fun spawnDaemon(): SupervisorContext {
        val daemonClassName = SupervisorDaemon::class.java.name

        val perms = PosixFilePermissions.fromString("rwx------")
        val socketDir = processLauncher.createTempDirectory("mazewall-supervisor-", PosixFilePermissions.asFileAttribute(perms))
        val socketPath = socketDir.resolve("supervisor.sock").toAbsolutePath().toString()

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

        engine.withTransaction {
            engine.process.prctl(
                io.mazewall.core.PrctlCommand.SetPtracer(daemonPid)
            ).onFailure { errno, _ ->
                logger.warning("prctl(PR_SET_PTRACER) failed with errno $errno. The daemon may not be able to read process memory if Yama ptrace_scope is restrictive.")
            }
            Unit
        }

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

        val shutdownHook = Thread {
            daemonProcess.destroyForcibly()
        }
        processLauncher.addShutdownHook(shutdownHook)

        return SupervisorContext(socketPath, socketDir, daemonProcess, shutdownHook)
    }

    private fun triggerDaemonShutdown(socketPath: String) {
        try {
            Arena.ofConfined().use { arena ->
                val fd = socketManager.connect(socketPath)
                try {
                    val cmd = arena.allocateFrom(ValueLayout.JAVA_BYTE, SHUTDOWN_COMMAND_BYTE)
                    var writeRes: io.mazewall.LinuxNative.SyscallResult<Long, *>
                    while (true) {
                        writeRes = engine.withTransaction { engine.memory.write(fd, cmd, 1) }
                        if (writeRes is io.mazewall.LinuxNative.SyscallResult.Error && writeRes.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                            continue
                        }
                        break
                    }
                    Thread.sleep(SHUTDOWN_WAIT_MS)
                } finally {
                    socketManager.close(fd)
                }
            }
        } catch (ignored: Exception) {
            // Ignore
        }
    }
}
