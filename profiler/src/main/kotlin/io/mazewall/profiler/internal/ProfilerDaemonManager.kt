package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.getFdOrThrow
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.Logger

/**
 * Context for a running Profiler Daemon.
 */
public data class DaemonContext(
    val socketPath: String,
    val socketDir: Path,
    val daemonProcess: Process,
    val shutdownHook: Thread,
)

/**
 * Manages the lifecycle of the shared Profiler Daemon.
 */
public class ProfilerDaemonManager(
    private val engine: NativeEngine = LinuxNative,
    private val socketManager: SocketManager = RealSocketManager,
    private val processLauncher: ProcessLauncher = RealProcessLauncher
) {
    private val logger = Logger.getLogger(ProfilerDaemonManager::class.java.name)
    private val daemonLock = Any()
    private var sharedDaemonContext: DaemonContext? = null

    public companion object {
        private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte() // 'S'
        private const val SHUTDOWN_WAIT_MS = 100L
        private val INSTANCE_DEFAULT = ProfilerDaemonManager()

        @JvmStatic
        public fun getInstance(): ProfilerDaemonManager = INSTANCE_DEFAULT
    }

    /**
     * Returns the existing shared daemon context or spawns a new one.
     */
    public fun getOrSpawnSharedDaemon(): DaemonContext {
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

    public fun cleanupDaemon(context: DaemonContext) {
        try {
            processLauncher.removeShutdownHook(context.shutdownHook)
        } catch (e: IllegalStateException) {
            // Shutdown already in progress - ignore
            logger.log(java.util.logging.Level.FINE, "Shutdown hook removal skipped: shutdown in progress", e)
        } catch (e: SecurityException) {
            logger.log(java.util.logging.Level.WARNING, "Failed to remove shutdown hook", e)
        }
        triggerDaemonShutdown(context.socketPath)
        context.daemonProcess.destroyForcibly()
        context.daemonProcess.waitFor()
        try {
            processLauncher.deleteIfExists(context.socketDir.resolve("profiler.sock"))
            processLauncher.deleteIfExists(context.socketDir)
        } catch (e: IOException) {
            logger.log(
                java.util.logging.Level.WARNING,
                "Failed to delete secure socket directory at ${context.socketDir}",
                e,
            )
        }
    }

    private fun spawnDaemon(): DaemonContext {
        val daemonClassName = io.mazewall.profiler.engine.ProfilerDaemon::class.java.name

        val perms = PosixFilePermissions.fromString("rwx------")
        val socketDir = processLauncher.createTempDirectory("mazewall-profiler-", PosixFilePermissions.asFileAttribute(perms))
        val socketPath = socketDir.resolve("profiler.sock").toAbsolutePath().toString()

        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")

        val jvmArgs = java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
        val jacocoAgent = jvmArgs.find { it.startsWith("-javaagent:") && it.contains("jacoco") }

        val pbArgs = mutableListOf<String>()
        pbArgs.add(javaBin)
        pbArgs.add("--enable-native-access=ALL-UNNAMED")
        pbArgs.add("-Xmx64m")
        if (jacocoAgent != null) {
            pbArgs.add(jacocoAgent)
        }
        pbArgs.add("-cp")
        pbArgs.add(classpath)
        pbArgs.add(daemonClassName)
        pbArgs.add(socketPath)

        logger.info("Spawning ProfilerDaemon: ${pbArgs.joinToString(" ")}")

        val daemonProcess = processLauncher.startProcess(pbArgs)
        val daemonPid = daemonProcess.pid()

        val prctlRes =
        engine.process.prctl(
            io.mazewall.core.PrctlCommand.SetPtracer(daemonPid)
        )

        if (prctlRes is io.mazewall.LinuxNative.SyscallResult.Error) {
            logger.warning("prctl(PR_SET_PTRACER) failed with errno ${prctlRes.errno}. The daemon may not be able to read process memory if Yama ptrace_scope is restrictive.")
        }

        val readyLatch = java.util.concurrent.CountDownLatch(1)

        Thread {
            try {
                val reader = daemonProcess.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains(io.mazewall.profiler.engine.DAEMON_READY_SENTINEL)) {
                        readyLatch.countDown()
                    }
                    System.err.println("[DAEMON] $line")
                    System.err.flush()
                }
            } catch (e: IOException) {
                logger.log(java.util.logging.Level.FINE, "Daemon output reader stopped", e)
            }
        }.apply {
            isDaemon = true
            name = "profiler-daemon-output"
        }.start()

        // Wait for sentinel with 30s timeout
        @Suppress("MagicNumber")
        val ready = readyLatch.await(30, java.util.concurrent.TimeUnit.SECONDS)

        if (!ready) {
            val alive = daemonProcess.isAlive
            val exitCode = if (!alive) daemonProcess.exitValue() else -1
            if (alive) daemonProcess.destroyForcibly()

            throw IllegalStateException(
                "ProfilerDaemon failed to signal readiness within 30s (exitCode=$exitCode). " +
                    "Check [DAEMON] logs above.",
            )
        }

        val shutdownHook = Thread {
            daemonProcess.destroyForcibly()
        }
        processLauncher.addShutdownHook(shutdownHook)

        return DaemonContext(socketPath, socketDir, daemonProcess, shutdownHook)
    }

    private fun triggerDaemonShutdown(socketPath: String) {
        try {
            Arena.ofConfined().use { arena ->
                val fd = socketManager.connect(socketPath)
                try {
                    val cmd = arena.allocate(1)
                    cmd.set(ValueLayout.JAVA_BYTE, 0L, SHUTDOWN_COMMAND_BYTE)
                    engine.memory.write(fd, ConfinedSegment(cmd), 1)
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
