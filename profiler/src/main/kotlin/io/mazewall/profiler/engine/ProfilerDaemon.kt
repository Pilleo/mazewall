package io.mazewall.profiler.engine

import java.io.IOException
import kotlin.system.exitProcess

/**
 * Main entry point for the Profiler Daemon process.
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
