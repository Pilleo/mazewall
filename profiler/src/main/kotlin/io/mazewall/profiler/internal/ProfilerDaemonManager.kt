package io.mazewall.profiler.internal

import io.mazewall.LinuxNative
import io.mazewall.core.NativeArg
import io.mazewall.ffi.NativeConstants
import io.mazewall.getFdOrThrow
import io.mazewall.onSuccess
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.Logger

data class DaemonContext(
    val socketPath: String,
    val socketDir: Path,
    val daemonProcess: Process,
    val shutdownHook: Thread,
)

internal object ProfilerDaemonManager {
    private val logger = Logger.getLogger(ProfilerDaemonManager::class.java.name)
    private val daemonLock = Any()
    private var sharedDaemonContext: DaemonContext? = null

    private const val SHUTDOWN_COMMAND_BYTE = 0x53.toByte() // 'S'
    private const val SHUTDOWN_WAIT_MS = 100L

    fun getOrSpawnSharedDaemon(): DaemonContext {
        synchronized(daemonLock) {
            val existing = sharedDaemonContext
            if (existing != null && existing.daemonProcess.isAlive) {
                LinuxNative.withTransaction {
                    LinuxNative.process.prctl(
                        NativeConstants.PR_SET_PTRACER,
                        NativeArg.LongArg(existing.daemonProcess.pid()),
                        NativeArg.NullArg,
                        NativeArg.NullArg,
                        NativeArg.NullArg,
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

    fun cleanupDaemon(context: DaemonContext) {
        try {
            Runtime.getRuntime().removeShutdownHook(context.shutdownHook)
        } catch (e: IllegalStateException) {
            // Shutdown already in progress - ignore
            logger.log(java.util.logging.Level.FINE, "Shutdown hook removal skipped: shutdown in progress", e)
        } catch (e: SecurityException) {
            logger.log(java.util.logging.Level.WARNING, "Failed to remove shutdown hook", e)
        }
        triggerDaemonShutdown(context.socketPath)
        context.daemonProcess.destroyForcibly()
        try {
            Files.deleteIfExists(context.socketDir.resolve("profiler.sock"))
            Files.deleteIfExists(context.socketDir)
        } catch (e: IOException) {
            logger.log(
                java.util.logging.Level.WARNING,
                "Failed to delete secure socket directory at ${context.socketDir}",
                e,
            )
        }
    }

    private fun spawnDaemon(): DaemonContext {
        // Diagnostic: ensure the daemon class is accessible (provides compile-time safety)
        val daemonClassName = io.mazewall.profiler.engine.ProfilerDaemon::class.java.name

        val perms = PosixFilePermissions.fromString("rwx------")
        val socketDir = Files.createTempDirectory("mazewall-profiler-", PosixFilePermissions.asFileAttribute(perms))
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
        if (jacocoAgent != null) {
            pbArgs.add(jacocoAgent)
        }
        pbArgs.add("-cp")
        pbArgs.add(classpath)
        pbArgs.add(daemonClassName)
        pbArgs.add(socketPath)

        logger.info("Spawning ProfilerDaemon: ${pbArgs.joinToString(" ")}")

        val pb = ProcessBuilder(pbArgs)
        pb.redirectErrorStream(true)
        val daemonProcess = pb.start()
        val daemonPid = daemonProcess.pid()

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

        // Wait for sentinel with 30s timeout (generous for slow CI)
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

        val prctlRes = LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                NativeConstants.PR_SET_PTRACER,
                NativeArg.LongArg(daemonPid),
                NativeArg.NullArg,
                NativeArg.NullArg,
                NativeArg.NullArg,
            )
        }
        if (prctlRes is LinuxNative.SyscallResult.Error) {
            logger.warning("prctl(PR_SET_PTRACER) failed with errno ${prctlRes.errno}. The daemon may not be able to read process memory if Yama ptrace_scope is restrictive.")
        }

        val shutdownHook = Thread {
            daemonProcess.destroyForcibly()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        return DaemonContext(socketPath, socketDir, daemonProcess, shutdownHook)
    }

    private fun triggerDaemonShutdown(socketPath: String) {
        try {
            Arena.ofConfined().use { arena ->
                val (fdRes, addr) = LinuxNative.withTransaction {
                    val r1 = LinuxNative.networking.socket(ProfilerSocket.AF_UNIX, ProfilerSocket.SOCK_STREAM, 0)
                    if (r1 is LinuxNative.SyscallResult.Error) return@withTransaction r1 to null
                    r1 to ProfilerSocket.setupSockAddrUn(arena, socketPath)
                }
                if (fdRes is LinuxNative.SyscallResult.Error) return
                val fd = fdRes.getFdOrThrow("socket(AF_UNIX)")
                try {
                    val connRes = LinuxNative.withTransaction { LinuxNative.networking.connect(fd, addr!!, ProfilerSocket.ADDR_UN_SIZE) }
                    if (connRes is LinuxNative.SyscallResult.Success) {
                        val cmd = arena.allocate(1)
                        cmd.set(ValueLayout.JAVA_BYTE, 0L, SHUTDOWN_COMMAND_BYTE)
                        LinuxNative.withTransaction { LinuxNative.memory.write(fd, cmd, 1) }
                        Thread.sleep(SHUTDOWN_WAIT_MS)
                    }
                } finally {
                    LinuxNative.fileSystem.close(fd)
                }
            }
        } catch (e: InterruptedException) {
            logger.log(java.util.logging.Level.FINE, "Daemon shutdown signal interrupted (harmless)", e)
            Thread.currentThread().interrupt()
        } catch (e: IllegalArgumentException) {
            logger.log(java.util.logging.Level.FINE, "Daemon shutdown signal failed (harmless)", e)
        } catch (e: IllegalStateException) {
            logger.log(java.util.logging.Level.FINE, "Daemon shutdown signal failed (harmless)", e)
        } catch (e: UnsupportedOperationException) {
            logger.log(java.util.logging.Level.FINE, "Daemon shutdown signal failed (harmless)", e)
        }
    }
}
