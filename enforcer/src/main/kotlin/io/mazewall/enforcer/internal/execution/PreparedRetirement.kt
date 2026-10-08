package io.mazewall.enforcer.internal.execution

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Published once, after release and failure retention; physical thread end is checked separately. */
internal data class PreparedRetirementOutcome(
    val releaseAttempted: Boolean,
    val failure: Throwable?,
)

/** Trusted bootstrap starts this retained cleanup owner; caller deadlines never run blocking release. */
internal class PreparedRetirement(
    name: String,
    private val awaitWorkers: () -> Unit,
    private val release: () -> Unit,
    private val recordFailure: (Throwable) -> Unit,
) {
    private val bootstrap = Thread.currentThread()
    private val requested = CountDownLatch(1)

    private val publication = AtomicReference<PreparedRetirementOutcome?>()
    private val thread = Thread
        .ofPlatform()
        .name(name)
        .inheritInheritableThreadLocals(false)
        .unstarted { run() }

    fun start() {
        check(Thread.currentThread() === bootstrap) { "Prepared retirement must start on its trusted bootstrap thread" }
        thread.start()
    }

    fun request() = requested.countDown()

    fun await(deadline: Deadline) = deadline.join(thread)

    fun outcome(): PreparedRetirementOutcome? = if (thread.isAlive) null else publication.get()

    fun finished(): Boolean = outcome()?.releaseAttempted == true

    fun live(): Boolean = thread.isAlive

    /** A failed native thread start has not acquired a live thread; bootstrap retains rollback. */
    @Suppress("TooGenericExceptionCaught") // start rollback keeps original/fatal cleanup identity
    fun releaseUnstarted() {
        if (thread.state != Thread.State.NEW) {
            request()
            return
        }
        var attempted = false
        var problem: Throwable? = null
        try {
            awaitWorkers()
            attempted = true
            release()
        } catch (failure: Throwable) {
            problem = failure
            recordFailure(failure)
            throw failure
        } finally {
            publication.set(PreparedRetirementOutcome(attempted, problem))
        }
    }

    @Suppress("TooGenericExceptionCaught") // retained physical cleanup boundary never downgrades failure
    private fun run() {
        var interrupted = false
        var attempted = false
        var problem: Throwable? = null
        try {
            while (requested.count != 0L) {
                try {
                    requested.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            awaitWorkers()
            attempted = true
            release()
        } catch (fatal: Error) {
            problem = fatal
            recordFailure(fatal)
            throw fatal
        } catch (failure: Throwable) {
            problem = failure
            recordFailure(failure)
        } finally {
            publication.set(PreparedRetirementOutcome(attempted, problem))
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
