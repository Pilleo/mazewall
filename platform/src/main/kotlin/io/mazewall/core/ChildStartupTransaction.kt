package io.mazewall.core

import io.mazewall.MazewallInternal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Acquires one child JVM's resources; ownership transfers only after readiness is verified. */
@MazewallInternal
public class ChildStartupTransaction(
    launcher: ProcessLauncher,
    directoryPrefix: String,
    socketName: String,
) : AutoCloseable {
    private val resources = ChildProcessResources(launcher, PrivateUnixEndpoint.create(launcher, directoryPrefix, socketName))
    private var committed = false
    private var ready = false
    public val endpoint: PrivateUnixEndpoint get() = resources.endpoint
    public val shutdownHook: Thread get() = resources.shutdownHook

    /**
     * Runs startup with fatal-first rollback aggregation. Retain this transaction before calling:
     * if rollback fails, its owner must retry [close] before starting another child.
     * The action must [commit] before publishing a successful result.
     */
    public fun <T> runStartup(action: (ChildStartupTransaction) -> T): T =
        ResourceRetirement.runWithRollback(
            { action(this).also { check(committed) { "Startup returned without committing its resource owner" } } },
            ::close,
        )

    public fun start(spec: JvmChildSpec): Process {
        check(!committed && !resources.isClosed && resources.child == null) { "Startup closed or child already acquired" }
        return JvmChildProcess.start(resources.launcher, spec).also { resources.child = it }
    }

    public fun startOutputPump(
        sentinel: String,
        onLine: (String) -> Unit,
        threadName: String,
        onStreamClosed: () -> Unit = {},
    ) {
        check(!committed && !resources.isClosed && resources.pump == null) { "Startup closed or output pump already acquired" }
        resources.pump = JvmChildProcess.startStdoutPump(resources.process, sentinel, onLine, threadName, onStreamClosed)
    }

    public fun registerShutdownHook() {
        check(!committed && !resources.isClosed && resources.hook == null) { "Startup closed or shutdown hook already acquired" }
        val hook = Thread({ resources.close() }, "mazewall-child-shutdown")
        resources.launcher.addShutdownHook(hook)
        resources.hook = hook
    }

    public fun awaitReady(timeoutSeconds: Long) {
        check(!committed && !resources.isClosed)
        val pump = checkNotNull(resources.pump) { "Output pump not acquired" }
        val observed = try {
            JvmChildProcess.awaitReady(pump, timeoutSeconds)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        check(observed && resources.process.isAlive) {
            "Child failed to signal readiness while alive within ${timeoutSeconds}s; check child output"
        }
        ready = true
    }

    public fun commit(): ChildProcessResources {
        check(!committed && !resources.isClosed && ready && resources.hook != null && resources.process.isAlive) {
            "Cannot commit child before readiness and shutdown-hook registration"
        }
        committed = true
        return resources
    }

    override fun close() {
        if (!committed) resources.close()
    }
}

/** Sole cleanup owner after startup commits; managers retain their independent lifecycle policies. */
@MazewallInternal
public class ChildProcessResources internal constructor(
    internal val launcher: ProcessLauncher,
    public val endpoint: PrivateUnixEndpoint,
) : AutoCloseable {
    private companion object {
        const val PUMP_JOIN_TIMEOUT_MS = 1_000L
    }

    internal var child: Process? = null
    internal var pump: ChildStdoutPump? = null
    internal var hook: Thread? = null
    private val closed = AtomicBoolean()
    private var retired = false
    private var reaped = false
    private var inputClosed = false
    private var errorClosed = false
    private var outputClosed = false
    private var endpointClosed = false
    public val isClosed: Boolean get() = closed.get()
    public val process: Process get() = checkNotNull(child) { "Child not acquired" }
    public val shutdownHook: Thread get() = checkNotNull(hook) { "Shutdown hook not acquired" }

    @Synchronized override fun close() {
        if (retired) return
        closed.set(true)
        val cleanup = ResourceRetirement()
        val retire = cleanup::attempt
        hook?.let { hook ->
            retire {
                if (Thread.currentThread() !== hook) {
                    try {
                        launcher.removeShutdownHook(hook)
                    } catch (_: IllegalStateException) {
                        // JVM shutdown already owns this hook; every other resource still retires below.
                    }
                }
                this.hook = null
            }
        }
        child?.let { process ->
            if (!reaped) {
                retire { process.destroyForcibly() }
                retire {
                    check(process.waitFor(2, TimeUnit.SECONDS)) { "Child did not exit after forced termination" }
                    reaped = true
                }
            }
            if (!inputClosed) {
                retire {
                    process.inputStream.close()
                    inputClosed = true
                }
            }
            if (!errorClosed) {
                retire {
                    process.errorStream.close()
                    errorClosed = true
                }
            }
            if (!outputClosed) {
                retire {
                    process.outputStream.close()
                    outputClosed = true
                }
            }
        }
        pump?.let { pump ->
            retire {
                pump.thread.interrupt()
                if (Thread.currentThread() !== pump.thread) {
                    pump.thread.join(PUMP_JOIN_TIMEOUT_MS)
                    check(!pump.thread.isAlive) { "Child output pump did not retire" }
                }
                this.pump = null
            }
        }
        if (!endpointClosed) {
            retire {
                endpoint.close()
                endpointClosed = true
            }
        }
        cleanup.finish()
        retired = true
    }
}
