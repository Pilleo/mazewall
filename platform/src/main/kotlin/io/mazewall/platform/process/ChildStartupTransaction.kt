package io.mazewall.platform.process

import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.MazewallInternal
import io.mazewall.platform.socket.PrivateUnixEndpoint
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Acquires one child JVM's resources; ownership transfers only after readiness is verified. */
@MazewallInternal
internal class ChildStartupTransaction private constructor(
    launcher: ProcessLauncher,
    directoryPrefix: String,
    socketName: String,
    confined: Boolean,
) : AutoCloseable {
    internal constructor(
        launcher: ProcessLauncher,
        directoryPrefix: String,
        socketName: String,
    ) : this(launcher, directoryPrefix, socketName, false)

    internal companion object {
        internal fun confined(
            launcher: ProcessLauncher,
            directoryPrefix: String,
            socketName: String,
        ): ChildStartupTransaction = ChildStartupTransaction(launcher, directoryPrefix, socketName, true)
    }

    private val confinedState = if (confined) ConfinedHookState() else null
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
    ): Unit =
        acquireOutputPump {
            JvmChildProcess.startStdoutPump(resources.process, sentinel, onLine, threadName, onStreamClosed)
        }

    internal fun startBoundedOutputPump(
        sentinel: String,
        maxLineChars: Int,
        onLine: (String) -> Unit,
        onOversizedLine: () -> Unit,
        threadName: String,
        onStreamClosed: () -> Unit,
    ): Unit =
        acquireOutputPump {
            JvmChildProcess.startBoundedStdoutPump(
                resources.process,
                sentinel,
                maxLineChars,
                onLine,
                onOversizedLine,
                threadName,
                onStreamClosed,
            )
        }

    private fun acquireOutputPump(startPump: () -> ChildStdoutPump) {
        check(!committed && !resources.isClosed && resources.pump == null) { "Startup closed or output pump already acquired" }
        resources.pump = startPump()
    }

    public fun registerShutdownHook() = registerShutdownHook {}

    @Suppress("TooGenericExceptionCaught") // Preserve the original registration failure before rollback.
    public fun registerShutdownHook(beforeClose: () -> Unit) {
        check(!committed && !resources.isClosed && resources.hook == null) { "Startup closed or shutdown hook already acquired" }
        val hook = Thread({
            confinedState?.beginHookClose()
            val cleanup = ResourceRetirement()
            cleanup.attempt(beforeClose)
            cleanup.attempt(resources::close)
            cleanup.finish()
        }, "mazewall-child-shutdown")
        val state = confinedState
        if (state != null) state.dispatchedHook = hook
        try {
            resources.launcher.addShutdownHook(hook)
        } catch (failure: Throwable) {
            state?.recordRegistration(failure)
            throw failure
        }
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
        val state = confinedState
        check(state == null || state.claimCommit()) { "Confined child closed before commit" }
        committed = true
        return resources
    }

    override fun close() {
        if (!committed) {
            val state = confinedState
            if (state != null && state.registrationFailure != null) {
                closeAfterFailedRegistration(state)
            } else {
                resources.close()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Retain arbitrary original cleanup failure before the independent removal.
    private fun closeAfterFailedRegistration(state: ConfinedHookState) {
        try {
            resources.close()
        } catch (failure: Throwable) {
            state.recordCleanup(failure)
        }
        var interrupted = Thread.interrupted()
        if (state.remove(resources.launcher)) interrupted = true
        if (interrupted) Thread.currentThread().interrupt()
        val failure = state.failure()
        if (failure != null) throw failure
    }

    private class ConfinedHookState {
        private var hookClosing = false
        private var commitClaimed = false

        @Synchronized
        fun beginHookClose() {
            hookClosing = true
        }

        @Synchronized
        fun claimCommit(): Boolean {
            if (hookClosing || commitClaimed) return false
            commitClaimed = true
            return true
        }

        @Volatile var dispatchedHook: Thread? = null

        @Volatile var registrationFailure: Throwable? = null
        private val removalDispatched = AtomicBoolean()

        @Volatile private var removalCompleted = false
        private val foldDispatched = AtomicBoolean()

        @Volatile private var foldCompleted = false
        private val removalFailure = AtomicReference<Throwable?>()
        private val firstCleanupFailure = AtomicReference<Throwable?>()
        private val firstFatal = AtomicReference<Error?>()
        private val metadataFailure = AtomicReference<Throwable?>()

        fun recordRegistration(failure: Throwable) {
            recordFatal(failure)
            registrationFailure = failure
        }

        fun recordCleanup(failure: Throwable) {
            firstCleanupFailure.compareAndSet(null, failure)
            recordFatal(failure)
        }

        private fun recordFatal(failure: Throwable) {
            if (failure is Error) firstFatal.compareAndSet(null, failure)
        }

        @Suppress("TooGenericExceptionCaught") // Retain the original arbitrary removal failure before folding.
        fun remove(launcher: ProcessLauncher): Boolean {
            val hook = checkNotNull(dispatchedHook)
            var interrupted = false
            if (Thread.currentThread() !== hook && removalDispatched.compareAndSet(false, true)) {
                try {
                    launcher.removeShutdownHook(hook)
                } catch (failure: InterruptedException) {
                    removalFailure.set(failure)
                    interrupted = true
                    Thread.interrupted()
                } catch (failure: Throwable) {
                    removalFailure.set(failure)
                    recordFatal(failure)
                } finally {
                    removalCompleted = true
                }
            }
            return interrupted
        }

        fun failure(): Throwable? {
            if (!removalCompleted) return firstFatal.get() ?: checkNotNull(registrationFailure)
            val cleanup = firstCleanupFailure.get()
            val removal = removalFailure.get()
            if (cleanup != null && removal != null) fold(cleanup, removal)
            if (cleanup != null && removal != null && !foldCompleted) return firstFatal.get() ?: checkNotNull(registrationFailure)
            return firstFatal.get() ?: cleanup ?: removal ?: metadataFailure.get()
        }

        @Suppress("TooGenericExceptionCaught") // Preserve raw metadata failure without retrying a broken fold.
        private fun fold(cleanup: Throwable, removal: Throwable) {
            if (foldDispatched.compareAndSet(false, true)) {
                try {
                    CleanupFailures.merge(cleanup, removal)
                } catch (failure: Throwable) {
                    metadataFailure.compareAndSet(null, failure)
                    recordFatal(failure)
                } finally {
                    foldCompleted = true
                }
            }
        }
    }
}

/**
 * Sole cleanup owner after startup commits: endpoint, process, output pump, and shutdown hook.
 * Close attempts each independent release, retains failed owners for retry, and is idempotent
 * after successful retirement. Managers retain their independent lifecycle policies.
 */
@MazewallInternal
internal class ChildProcessResources internal constructor(
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
