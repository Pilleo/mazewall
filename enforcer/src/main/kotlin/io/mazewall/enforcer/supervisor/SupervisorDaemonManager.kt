package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import io.mazewall.ffi.Layouts
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.Logger

internal data class SupervisorContext(
    val socketPath: String,
    val socketDir: Path,
    val daemonProcess: Process,
    val shutdownHook: Thread,
)

internal object SupervisorDaemonManager {
    private val logger = Logger.getLogger(SupervisorDaemonManager::class.java.name)
    private val daemonLock = Any()
    private var sharedDaemonContext: SupervisorContext? = null

    private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte() // 'S'
    private const val SHUTDOWN_WAIT_MS = 100L
    private const val SOCKADDR_UN_SIZE = 110
    private const val LATCH_TIMEOUT_SECONDS = 30L

    val daemonLogLines = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun getOrSpawnSharedDaemon(): SupervisorContext {
        synchronized(daemonLock) {
            val existing = sharedDaemonContext
            System.err.println("DEBUG: getOrSpawnSharedDaemon - existing = ${existing?.socketPath} (alive=${existing?.daemonProcess?.isAlive})")
            if (existing != null && existing.daemonProcess.isAlive) {
                LinuxNative.withTransaction {
                    LinuxNative.process.prctl(
                        io.mazewall.core.PrctlCommand.SetPtracer(existing.daemonProcess.pid())
                    )
                }
                return existing
            }
            val newContext = spawnDaemon()
            sharedDaemonContext = newContext
            return newContext
        }
    }

    fun stop() {
        synchronized(daemonLock) {
            sharedDaemonContext?.let {
                cleanupDaemon(it)
                sharedDaemonContext = null
            }
        }
    }

    private fun cleanupDaemon(context: SupervisorContext) {
        try {
            Runtime.getRuntime().removeShutdownHook(context.shutdownHook)
        } catch (ignored: IllegalStateException) {
            // Shutdown already in progress - ignore
        } catch (e: SecurityException) {
            logger.log(java.util.logging.Level.WARNING, "Failed to remove shutdown hook", e)
        }
        triggerDaemonShutdown(context.socketPath)
        context.daemonProcess.destroyForcibly()
        try {
            Files.deleteIfExists(context.socketDir.resolve("supervisor.sock"))
            Files.deleteIfExists(context.socketDir)
        } catch (e: IOException) {
            logger.log(
                java.util.logging.Level.WARNING,
                "Failed to delete secure socket directory at ${context.socketDir}",
                e,
            )
        }
    }

    private fun spawnDaemon(): SupervisorContext {
        System.err.println("DEBUG: spawnDaemon called!")
        val daemonClassName = SupervisorDaemon::class.java.name

        val perms = PosixFilePermissions.fromString("rwx------")
        val socketDir = Files.createTempDirectory("mazewall-supervisor-", PosixFilePermissions.asFileAttribute(perms))
        val socketPath = socketDir.resolve("supervisor.sock").toAbsolutePath().toString()

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

        logger.info("Spawning SupervisorDaemon: ${pbArgs.joinToString(" ")}")

        val pb = ProcessBuilder(pbArgs)
        pb.redirectErrorStream(true)
        val daemonProcess = pb.start()
        val daemonPid = daemonProcess.pid()

        LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                io.mazewall.core.PrctlCommand.SetPtracer(daemonPid)
            )
        }

        val readyLatch = java.util.concurrent.CountDownLatch(1)

        Thread {
            try {
                val logFile = java.io.File("/tmp/supervisor_daemon.log")
                try {
                    logFile.writeText("") // Clear previous log
                } catch (e: Exception) {
                    System.err.println("DEBUG: logFile.writeText failed: ${e.message}")
                    e.printStackTrace()
                }
                val reader = daemonProcess.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains(SupervisorDaemon.DAEMON_READY_SENTINEL)) {
                        readyLatch.countDown()
                    }
                    daemonLogLines.add(line)
                    try {
                        logFile.appendText("$line\n")
                    } catch (e: Exception) {
                        System.err.println("DEBUG: logFile.appendText failed: ${e.message}")
                        e.printStackTrace()
                    }
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

        val prctlRes = LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                io.mazewall.core.PrctlCommand.SetPtracer(daemonPid)
            )
        }
        if (prctlRes is LinuxNative.SyscallResult.Error) {
            logger.warning("prctl(PR_SET_PTRACER) failed with errno ${prctlRes.errno}.")
        }

        val shutdownHook = Thread {
            daemonProcess.destroyForcibly()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        return SupervisorContext(socketPath, socketDir, daemonProcess, shutdownHook)
    }

    private fun triggerDaemonShutdown(socketPath: String) {
        try {
            Arena.ofConfined().use { arena ->
                val fdRes = LinuxNative.withTransaction {
                    LinuxNative.networking.socket(
                        io.mazewall.ffi.networking.SupervisorSocketUtils.AF_UNIX,
                        io.mazewall.ffi.networking.SupervisorSocketUtils.SOCK_STREAM,
                        0
                    )
                }
                val fd = fdRes.getFdOrThrow("socket(AF_UNIX)")
                try {
                    val sockaddrUn = io.mazewall.ffi.networking.SupervisorSocketUtils.setupSockAddrUn(arena, socketPath)

                    val connRes = LinuxNative.withTransaction {
                        LinuxNative.networking.connect(
                            fd,
                            sockaddrUn.segment,
                            io.mazewall.ffi.networking.SupervisorSocketUtils.SOCKADDR_UN_SIZE
                        )
                    }
                    if (connRes is LinuxNative.SyscallResult.Success) {
                        val cmd = arena.allocateFrom(ValueLayout.JAVA_BYTE, SHUTDOWN_COMMAND_BYTE)
                        LinuxNative.withTransaction { LinuxNative.memory.write(fd, cmd, 1) }
                        Thread.sleep(SHUTDOWN_WAIT_MS)
                    }
                } finally {
                    LinuxNative.fileSystem.close(fd)
                }
            }
        } catch (ignored: java.io.IOException) {
            // Ignore
        } catch (ignored: IllegalStateException) {
            // Ignore
        } catch (ignored: InterruptedException) {
            // Ignore
        }
    }
}
