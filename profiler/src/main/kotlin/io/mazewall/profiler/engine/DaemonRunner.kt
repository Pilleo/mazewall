package io.mazewall.profiler.engine

import java.io.IOException

public interface DaemonRunner {
    fun runDaemon(args: Array<String>)
}

public class RealDaemonRunner(
    private val exitFn: (Int) -> Unit = { kotlin.system.exitProcess(it) },
    private val stdinReader: java.io.Reader = System.`in`.reader(),
    private val engineFactory: (String) -> ProfilerDaemonEngine = { ProfilerDaemonEngine(it) },
    private val registerShutdownHook: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    private val removeShutdownHook: (Thread) -> Unit = Runtime.getRuntime()::removeShutdownHook,
) : DaemonRunner {
    override fun runDaemon(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: ProfilerDaemon <socket_path>")
            exitFn(1)
            return
        }
        val socketPath = args[0]
        val engine = engineFactory(socketPath)

        val hook = Thread { engine.triggerGlobalShutdown("JVM Shutdown Hook") }
        registerShutdownHook(hook)

        Thread {
            try {
                if (stdinReader.read() == -1) {
                    engine.triggerGlobalShutdown("Stdin EOF")
                }
            } catch (e: IOException) {
                engine.triggerGlobalShutdown("Stdin Error: ${e.message}")
            } finally {
                try {
                    removeShutdownHook(hook)
                } catch (ignored: Exception) {
                    }
                exitFn(0)
            }
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
