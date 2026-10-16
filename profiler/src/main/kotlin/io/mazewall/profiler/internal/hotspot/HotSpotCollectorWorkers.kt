package io.mazewall.profiler.internal.hotspot

import io.mazewall.platform.process.ProcessLauncher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val POLL_MILLIS = 5L

/** Retains every acquired process/thread/pipe until actual joins and checked stream retirement. */
internal class HotSpotCollectorWorkers(
    private val launcher: ProcessLauncher,
    private val limits: HotSpotReplayLimits,
    private val callbacks: HotSpotCollectorCallbacks,
) {
    private val process = AtomicReference<Process?>()
    private val leases = CopyOnWriteArrayList<Thread>()
    private val abortWanted = AtomicBoolean()
    private val abortStarted = AtomicBoolean()
    private val stopStarted = AtomicBoolean()
    private var retirement: Thread? = null
    private val inputClosed = AtomicBoolean()
    private val errorClosed = AtomicBoolean()
    private val outputClosed = AtomicBoolean()
    private val retired = AtomicBoolean()

    val released: Boolean get() = retired.get() && joined()

    fun launch(argv: List<String>) {
        worker("launch") {
            val acquired = launcher.startProcess(argv, redirectErrorStream = false)
            process.set(acquired)
            worker("stdout") {
                HotSpotWireReader(limits, 2uL).read(acquired.inputStream, callbacks::record)
                callbacks.event(HotSpotSessionEvent.StdoutEnded)
            }
            worker("stderr") {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var count = acquired.errorStream.read(buffer)
                while (count != -1) {
                    callbacks.diagnostics(buffer, count)
                    count = acquired.errorStream.read(buffer)
                }
                callbacks.event(HotSpotSessionEvent.StderrJoined)
            }
            worker("exit") { callbacks.event(HotSpotSessionEvent.ProcessExited(acquired.waitFor())) }
            if (abortWanted.get()) abort()
        }
        worker("timer") {
            while (callbacks.timerActive()) {
                callbacks.pulse()
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    fun requestStop() {
        if (stopStarted.compareAndSet(false, true)) {
            worker("control") {
                val acquired = checkNotNull(process.get()) { "Stop before child ownership" }
                acquired.outputStream.write("stop\n".toByteArray(Charsets.US_ASCII))
                acquired.outputStream.flush()
            }
        }
    }

    fun abort() {
        abortWanted.set(true)
        val acquired = process.get()
        if (acquired != null && abortStarted.compareAndSet(false, true)) {
            worker("abort") { if (acquired.isAlive) acquired.destroyForcibly() }
        }
    }

    @Synchronized
    fun tryRetire(retry: Boolean = false) {
        val previous = retirement
        if (retired.get() || (previous != null && (!retry || previous.isAlive)) || !joined() || process.get()?.isAlive == true) return
        retirement = worker("retire") {
            val acquired = process.get()
            if (acquired != null) {
                closeOwned(inputClosed) { acquired.inputStream.close() }
                closeOwned(errorClosed) { acquired.errorStream.close() }
                closeOwned(outputClosed) { acquired.outputStream.close() }
            }
            retired.set(acquired == null || (inputClosed.get() && errorClosed.get() && outputClosed.get()))
        }
    }

    @Suppress("TooGenericExceptionCaught") // Retain every failed close and continue independent retirement.
    private fun closeOwned(
        closed: AtomicBoolean,
        close: () -> Unit,
    ) {
        if (!closed.get()) {
            try {
                close()
                closed.set(true)
            } catch (failure: Throwable) {
                callbacks.event(HotSpotSessionEvent.Failure(failure))
            }
        }
    }

    private fun joined(): Boolean =
        leases.all { thread ->
        if (thread.state == Thread.State.NEW || thread.isAlive) {
            false
        } else {
            thread.join()
            true
        }
    }

    @Suppress("TooGenericExceptionCaught") // Never hide worker failure or its live child/pipe leases, including fatal errors.
    private fun worker(
        role: String,
        action: () -> Unit,
    ): Thread {
        val thread = Thread.ofPlatform().daemon(true).name("mazewall-hotspot-$role").unstarted {
            try {
                action()
            } catch (failure: Throwable) {
                callbacks.event(HotSpotSessionEvent.Failure(failure))
            }
        }
        leases.add(thread)
        thread.start()
        return thread
    }
}
