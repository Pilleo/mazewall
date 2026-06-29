package io.mazewall.enforcer.supervisor

import java.io.IOException
import kotlin.system.exitProcess

/**
 * Main entry point for the Supervisor Daemon process.
 */
public object SupervisorDaemon {
    public const val DAEMON_READY_SENTINEL: String = "MAZEWALL_SUPERVISOR_READY"

    @JvmStatic
    public fun main(args: Array<String>) {
        try {
            val rootLogger = java.util.logging.Logger.getLogger("")
            rootLogger.level = java.util.logging.Level.ALL
            for (handler in rootLogger.handlers) {
                handler.level = java.util.logging.Level.ALL
            }
        } catch (ignored: Exception) {}

        if (args.isEmpty()) {
            System.err.println("Usage: SupervisorDaemon <socket_path>")
            exitProcess(1)
        }
        val socketPath = args[0]
        val engine = SupervisorDaemonEngine(socketPath)

        Runtime.getRuntime().addShutdownHook(
            Thread { engine.triggerGlobalShutdown("JVM Shutdown Hook") }
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

        engine.run()
    }
}
