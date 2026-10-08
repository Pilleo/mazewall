package io.mazewall.enforcer.internal.execution

import java.util.concurrent.CountDownLatch

/** Trusted bootstrap starts this retained cleanup owner; caller deadlines never run blocking release. */
internal class PreparedRetirement(
    name: String,
    private val awaitWorkers: () -> Unit,
    private val release: () -> Unit,
    private val recordFailure: (Throwable) -> Unit,
) {
    private val bootstrap = Thread.currentThread()
    private val requested = CountDownLatch(1)

    @Volatile private var releaseFinished = false
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

    fun finished(): Boolean = releaseFinished && !thread.isAlive

    fun live(): Boolean = thread.isAlive

    /** A failed native thread start has not acquired a live thread; bootstrap retains rollback. */
    @Suppress("TooGenericExceptionCaught") // start rollback keeps original/fatal cleanup identity
    fun releaseUnstarted() {
        if (thread.state != Thread.State.NEW) {
            request()
            return
        }
        try {
            awaitWorkers()
            finishRelease()
        } catch (failure: Throwable) {
            recordFailure(failure)
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught") // retained physical cleanup boundary never downgrades failure
    private fun run() {
        var interrupted = false
        try {
            while (requested.count != 0L) {
                try {
                    requested.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            awaitWorkers()
            finishRelease()
        } catch (fatal: Error) {
            recordFailure(fatal)
            throw fatal
        } catch (failure: Throwable) {
            recordFailure(failure)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun finishRelease() {
        try {
            release()
        } finally {
            releaseFinished = true
        }
    }
}
