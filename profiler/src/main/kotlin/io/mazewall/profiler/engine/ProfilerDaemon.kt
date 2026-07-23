package io.mazewall.profiler.engine

import java.io.IOException
import kotlin.system.exitProcess

/**
 * Main entry point for the Profiler Daemon process.
 *
 * ### ⚠️ Security Warning & TOCTOU Limitations:
 * The USER_NOTIF Tier S Profiler (which this Profiler Daemon participates in) is inherently
 * vulnerable to concurrent memory mutation (TOCTOU / Time-of-Check to Time-of-Use) when resolving
 * pointer-based system call arguments (such as file paths) using `process_vm_readv`.
 *
 * This daemon and the associated profiling mechanism are strictly intended for profiling
 * trusted/benign workloads, not for intercepting or preventing malicious evasion attempts.
 * For robust, race-free, and kernel-enforced filesystem containment, **Landlock LSM** is the
 * preferred and recommended mechanism, as it evaluates and enforces path-based restrictions
 * directly in the kernel space at the inode level, making it completely immune to pointer-dereferencing TOCTOU attacks.
 */
object ProfilerDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: ProfilerDaemon <socket_path>")
            exitProcess(1)
        }
        val socketPath = args[0]
        val engine = ProfilerDaemonEngine(socketPath)

        Runtime.getRuntime().addShutdownHook(
            Thread { engine.triggerGlobalShutdown("JVM Shutdown Hook") },
        )

        Thread {
            try {
                if (System.`in`.read() == -1) {
                     engine.triggerGlobalShutdown("Stdin EOF")
                }
            } catch (e: IOException) {
                engine.triggerGlobalShutdown("Stdin Error: ${e.message}")
            }
            exitProcess(0)
        }.apply {
            isDaemon = true
            name = "stdin-monitor"
        }.start()

        try {
            engine.run()
        } catch (e: InterruptedException) {
            System.err.println("[DAEMON] Main loop interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        } catch (e: java.nio.channels.ClosedByInterruptException) {
            System.err.println("[DAEMON] Main loop channel closed by interrupt: ${e.message}")
            Thread.currentThread().interrupt()
        }
    }
}
