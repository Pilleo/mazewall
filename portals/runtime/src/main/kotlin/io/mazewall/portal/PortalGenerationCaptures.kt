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
        val capture = Capture { action(PortalMeasurementBudget(deadline, active)) }
        owners.add(capture)
        try {
            PortalMeasurementBudget(deadline, active).check()
            capture.thread.start()
            while (!capture.ended.await(POLL_MS, TimeUnit.MILLISECONDS)) PortalMeasurementBudget(deadline, active).check()
            PortalMeasurementBudget(deadline, active).check()
            return capture.result.join().getOrThrow()
        } finally {
            capture.cancel()
            if (capture.thread.state == Thread.State.NEW || capture.ended.count == 0L) owners.remove(capture)
        }
    }

    fun cancel() = owners.forEach(Capture<*>::cancel)

    fun retire(deadline: Deadline): Boolean {
        cancel()
        var fatal: Throwable? = null
        owners.toList().forEach { capture ->
            if (capture.ended.await(deadline.remainingMillis().coerceAtLeast(0).toLong(), TimeUnit.MILLISECONDS)) {
                owners.remove(capture)
                val outcome = capture.result.join().exceptionOrNull()
                if (outcome is Error) {
                    fatal = io.mazewall.platform.fd.CleanupFailures
                    .merge(fatal, outcome)
                }
            }
        }
        fatal?.let { throw it }
        return owners.isEmpty()
    }

    internal val retainedCount: Int get() = owners.size

    private class Capture<T>(
        action: () -> T,
    ) {
        val ended = CountDownLatch(1)
        val result = CompletableFuture<Result<T>>()
        val thread: Thread = Thread.ofPlatform().daemon(true).name("portal-generation-capture").unstarted {
            try {
                result.complete(runCatching(action))
            } finally {
                ended.countDown()
            }
        }

        fun cancel() = thread.interrupt()
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
