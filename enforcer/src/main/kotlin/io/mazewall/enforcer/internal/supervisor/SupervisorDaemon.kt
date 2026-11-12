package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Main entry point for the Supervisor Daemon process.
 */
internal object SupervisorDaemon {
    public const val DAEMON_READY_SENTINEL: String = "MAZEWALL_SUPERVISOR_READY"
    private val logger: java.util.logging.Logger = java.util.logging.Logger
        .getLogger(SupervisorDaemon::class.java.name)

    @Suppress("TooGenericExceptionCaught") // Startup observation refuses unsupported ancestry and preserves permission failures.
    internal fun observeConfinedBootstrap(
        parentPid: Pid,
        native: NativeEngine,
        observations: HelperSecurityObservation = HelperSecurityObservation(),
    ): SocketHelperProcess.ConfinedStartup {
        try {
            val pid = Pid(ProcessHandle.current().pid().toInt())
            val self = observations.observeAncestry(pid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
            val parent = observations.observeAncestry(parentPid, Tid(parentPid.value), HelperSecurityObservation.Lineage.Bootstrap)
            return SocketHelperProcess.ConfinedStartup(parent, self).also(SocketHelperProcess.ConfinedStartup::requireCommon)
        } catch (observationFailure: Exception) {
            throw ConfinedHelperRejection.classify(observationFailure, ConfinedRejectReason.Ancestry)
        }
    }

    internal fun provisioning(args: Array<String>): PoolProvisioning {
        require(args.size == 1 || args.size == 2 && args[1] == "--confined-read-v1") { "Usage: SupervisorDaemon <socket_path> [--confined-read-v1]" }
        require(args[0].isNotEmpty()) { "Supervisor socket path is empty" }
        return if (args.size == 1) PoolProvisioning.Legacy else PoolProvisioning.PrestartedConfined
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        try {
            val rootLogger = java.util.logging.Logger
                .getLogger("")
            rootLogger.level = java.util.logging.Level.ALL
            for (handler in rootLogger.handlers) {
                handler.level = java.util.logging.Level.ALL
            }
        } catch (ignored: Exception) {
            }

        val mode = provisioning(args)
        val socketPath = args[0]
        val engine = when (mode) {
            PoolProvisioning.Legacy -> SupervisorDaemonEngine(socketPath)
            PoolProvisioning.PrestartedConfined -> {
                val native = LinuxNative.snapshot()
                val parent = ProcessHandle.current().parent().orElseThrow()
                val startup = observeConfinedBootstrap(Pid(Math.toIntExact(parent.pid())), native)
                SupervisorDaemonEngine(socketPath, engine = native, socketHelpers = SocketHelperPool(mode) { SocketHelperProcess.startConfined() }, confinedBootstrap = startup)
            }
        }

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
        } catch (expectedFatalFailure: Throwable) {
            logger.log(java.util.logging.Level.SEVERE, "[SUPERVISOR-FATAL] Fatal error in SupervisorDaemon", expectedFatalFailure)
            exitProcess(1)
        }
    }
}
