package io.mazewall.portal

import io.mazewall.platform.lifecycle.Deadline
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owns physical blocking-I/O threads separately from result/cancellation completion. */
internal class PortalGenerationCaptures {
    private val owners = java.util.concurrent.ConcurrentHashMap
        .newKeySet<Capture<*>>()

    fun <T> measure(
        deadline: Deadline,
        active: () -> Boolean,
        action: (PortalMeasurementBudget) -> T,
    ): T {
        val cancelled = java.util.concurrent.atomic
            .AtomicBoolean()
        val authority = { active() && !cancelled.get() }
        val capture = Capture(cancelled) {
            val budget = PortalMeasurementBudget(deadline, authority)
            budget.check()
            action(budget)
        }
        owners.add(capture)
        try {
            PortalMeasurementBudget(deadline, authority).check()
            capture.thread.start()
            while (!capture.ended.await(POLL_MS, TimeUnit.MILLISECONDS)) PortalMeasurementBudget(deadline, authority).check()
            PortalMeasurementBudget(deadline, authority).check()
            val outcome = capture.result.join()
            capture.resultObserved = true
            return outcome.getOrThrow()
        } finally {
            capture.cancel()
            if (capture.mayForget) owners.remove(capture)
        }
    }

    fun cancel() = owners.forEach(Capture<*>::cancel)

    fun retire(deadline: Deadline): Boolean {
        cancel()
        var fatal: Throwable? = null
        owners.toList().forEach { capture ->
            if (capture.awaitPhysicalEnd(deadline)) {
                owners.remove(capture)
                val outcome = capture.result.join().exceptionOrNull()
                if (outcome is Error && !capture.resultObserved) {
                    fatal = io.mazewall.platform.fd.CleanupFailures
                    .merge(fatal, outcome)
                }
                capture.cancelFailure.get()?.let {
                    fatal = io.mazewall.platform.fd.CleanupFailures
                    .merge(fatal, it)
                }
            }
        }
        fatal?.let { throw it }
        return owners.isEmpty()
    }

    internal val retainedCount: Int get() = owners.size

    private class Capture<T>(
        private val cancelled: java.util.concurrent.atomic.AtomicBoolean,
        action: () -> T,
    ) {
        val ended = CountDownLatch(1)
        val result = CompletableFuture<Result<T>>()

        @Volatile var resultObserved = false
        private val cancellationEnded = CountDownLatch(1)
        val cancelFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val thread: Thread = Thread.ofPlatform().daemon(true).name("portal-generation-capture").unstarted {
            try {
                result.complete(runCatching(action))
            } finally {
                ended.countDown()
            }
        }

        private val cancellation = Thread.ofPlatform().daemon(true).name("portal-generation-cancel").unstarted {
            try {
                // Thread.interrupt may synchronously execute a channel's blocking close.
                // Its callback is physical IO too, never startup/control-plane work.
                cancelFailure.set(runCatching(thread::interrupt).exceptionOrNull())
            } finally {
                cancellationEnded.countDown()
            }
        }

        val physicallyEnded: Boolean get() = !thread.isAlive && !cancellation.isAlive && (thread.state == Thread.State.NEW || ended.count == 0L) && cancellationEnded.count == 0L

        val mayForget: Boolean get() = physicallyEnded &&
            cancelFailure.get() == null &&
            (resultObserved || result.getNow(null)?.exceptionOrNull() !is Error)

        fun awaitPhysicalEnd(deadline: Deadline): Boolean {
            if (physicallyEnded) return true
            return try {
                val signalled = ended.await(deadline.remainingMillis().coerceAtLeast(0).toLong(), TimeUnit.MILLISECONDS) &&
                    cancellationEnded.await(deadline.remainingMillis().coerceAtLeast(0).toLong(), TimeUnit.MILLISECONDS)
                if (signalled) {
                    joinWithin(thread, deadline)
                    joinWithin(cancellation, deadline)
                    physicallyEnded
                } else {
                    false
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false // Physical owners remain registered for a later retirement attempt.
            }
        }

        private fun joinWithin(
            owner: Thread,
            deadline: Deadline,
        ) {
            val remaining = deadline.remainingMillis()
            if (owner.isAlive && remaining > 0) owner.join(remaining.toLong())
        }

        @Suppress("TooGenericExceptionCaught") // Failed cancellation-thread acquisition must publish its actual outcome and end boundary.
        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            if (thread.state == Thread.State.NEW || ended.count == 0L) {
                cancellationEnded.countDown()
            } else {
                try {
                    cancellation.start()
                } catch (failure: Throwable) {
                    cancelFailure.set(failure)
                    cancellationEnded.countDown()
                    throw failure
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 10L
    }
}

internal fun interface PortalGenerationMeasurement {
    fun measure(
        classpath: String,
        launch: PortalWorkerLaunch?,
        budget: PortalMeasurementBudget,
    ): PortalWorkerGeneration

    companion object {
        val Actual = PortalGenerationMeasurement { classpath, launch, budget ->
            launch?.let { WorkerBundleMaterializer.verifyLaunch(it.artifact, classpath, budget) }
            val manifests = launch?.manifests ?: PortalWorkerGeneration.readManifests(classpath, budget)
            PortalWorkerGeneration.capture(classpath, launch?.artifact?.path, manifests, launch?.artifact?.sha256, budget)
        }
    }
}
