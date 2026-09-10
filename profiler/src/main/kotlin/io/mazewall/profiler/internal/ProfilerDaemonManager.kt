package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.ChildProcessResources
import io.mazewall.core.ChildStartupTransaction
import io.mazewall.core.JavaAgentSelection
import io.mazewall.core.JvmChildProcess
import io.mazewall.core.JvmChildSpec
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.getFdOrThrow
import io.mazewall.profiler.ffi.ProfilerNativeCommandWriter
import java.nio.file.Path
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
    private val processLauncher: ProcessLauncher = RealProcessLauncher,
) {
    private val logger = Logger.getLogger(ProfilerDaemonManager::class.java.name)
    private val daemonLock = Any()
    private var sharedDaemonContext: DaemonContext? = null
    private var ownedResources: ChildProcessResources? = null
    private var pendingStartup: ChildStartupTransaction? = null

    public companion object {
        private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte() // 'S'
        private const val SHUTDOWN_WAIT_MS = 100L
        private const val SHUTDOWN_POLL_INTERVAL_MS = 10L
        private const val LATCH_TIMEOUT_SECONDS = 30L
        private val INSTANCE_DEFAULT = ProfilerDaemonManager()

        @JvmStatic
        public fun getInstance(): ProfilerDaemonManager = INSTANCE_DEFAULT
    }

    /**
     * Returns the existing shared daemon context or spawns a new one.
     */
    public fun getOrSpawnSharedDaemon(): DaemonContext {
        synchronized(daemonLock) {
            retirePendingStartup()
            val existing = sharedDaemonContext
            if (existing != null && existing.daemonProcess.isAlive && ownedResources?.isClosed != true) {
                engine.process
                    .prctl(
                    io.mazewall.core.PrctlCommand
                        .SetPtracer(existing.daemonProcess.pid()),
                ).getOrThrow("prctl(PR_SET_PTRACER)")

                return existing
            }
            existing?.let { cleanupDaemon(it) }
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
            retirePendingStartup()
            sharedDaemonContext?.let {
                cleanupDaemon(it)
                sharedDaemonContext = null
            }
        }
    }

    public fun cleanupDaemon(context: DaemonContext) {
        synchronized(daemonLock) {
            val resources = ownedResources ?: return
            require(resources.process === context.daemonProcess) { "Cannot retire a daemon not owned by this manager" }
            resources.use {
                if (context.daemonProcess.isAlive) triggerDaemonShutdown(context.socketPath, context.daemonProcess)
            }
            sharedDaemonContext = null
            ownedResources = null
        }
    }

    private fun retirePendingStartup() {
        pendingStartup?.close()
        pendingStartup = null
    }

    private fun spawnDaemon(): DaemonContext {
        val startup = ChildStartupTransaction(processLauncher, "mazewall-profiler-", "profiler.sock")
        pendingStartup = startup
        val context = startup.runStartup(::startDaemon)
        pendingStartup = null
        return context
    }

    private fun startDaemon(startup: ChildStartupTransaction): DaemonContext {
        val endpoint = startup.endpoint
        val socketDir = endpoint.dir
        val socketPath = endpoint.path

        val spec =
            JvmChildSpec(
                mainClass = io.mazewall.profiler.engine.ProfilerDaemon::class.java.name,
                mainArgs = listOf(socketPath),
                maxHeap = "64m",
                javaAgents = JavaAgentSelection.JacocoOnly,
            )
        val pbArgs = JvmChildProcess.commandLine(spec)
        logger.info("Spawning ProfilerDaemon: ${pbArgs.joinToString(" ")}")

        val daemonProcess = startup.start(spec)
        val daemonPid = daemonProcess.pid()

        engine.process
            .prctl(
                io.mazewall.core.PrctlCommand
                    .SetPtracer(daemonPid),
            ).getOrThrow("prctl(PR_SET_PTRACER)")

        startup.startOutputPump(
                sentinel = io.mazewall.profiler.engine.DAEMON_READY_SENTINEL,
                onLine = { line ->
                    // Child diagnostics may include arbitrary tracee input. Readiness is
                    // consumed by the resource owner; ordinary output exposes only a size.
                    logger.fine("Profiler daemon diagnostic received (${line.length} characters)")
                },
                threadName = "profiler-daemon-output",
            )

        startup.registerShutdownHook()
        startup.awaitReady(LATCH_TIMEOUT_SECONDS)
        val context = DaemonContext(socketPath, socketDir, daemonProcess, startup.shutdownHook)
        val resources = startup.commit()
        ownedResources = resources
        return context
    }

    private fun triggerDaemonShutdown(
        socketPath: String,
        process: Process,
    ) {
        try {
            val fd = socketManager.connect(socketPath)
            try {
                ProfilerNativeCommandWriter.writeByte(engine.memory, fd, SHUTDOWN_COMMAND_BYTE)
            } finally {
                socketManager.close(fd)
            }
        } catch (ignored: Exception) {
            // Caller escalates via destroyForcibly().
            return
        }
        // Bounded liveness poll instead of a fixed sleep (issue-20260823-172000).
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
        try {
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(SHUTDOWN_POLL_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
