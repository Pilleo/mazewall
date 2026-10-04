package io.mazewall.profiler.internal.hotspot

import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import java.util.concurrent.atomic.AtomicReference

private const val POLL_MILLIS = 5L
private const val INTERRUPTED_POLL_NANOS = 1_000_000L

internal class HotSpotCollectorProcess private constructor(
    private val interpreter: HotSpotSessionInterpreter,
    private val driver: HotSpotSessionDriver,
    private val workers: HotSpotCollectorWorkers,
    private val timing: HotSpotCollectorTiming,
) : AutoCloseable {
    private val completed = AtomicReference<HotSpotCollectorSnapshot?>()

    companion object {
        fun attach(
            argv: List<String>,
            expectedPid: Int,
            expectedStartTicks: ULong,
            launcher: ProcessLauncher = RealProcessLauncher,
            timing: HotSpotCollectorTiming = HotSpotCollectorTiming(),
            limits: HotSpotReplayLimits = HotSpotReplayLimits(),
        ): HotSpotCollectorProcess {
            require(argv.isNotEmpty())
            val interpreter = HotSpotSessionInterpreter(expectedPid, expectedStartTicks, limits, timing)
            val driver = HotSpotSessionDriver(interpreter)
            val workers = HotSpotCollectorWorkers(launcher, limits, driver)
            driver.bind(workers, argv)
            val owner = HotSpotCollectorProcess(interpreter, driver, workers, timing)
            driver.event(HotSpotSessionEvent.Start)
            owner.awaitReady()
            return owner
        }
    }

    fun snapshot(): HotSpotCollectorSnapshot = completed.get() ?: interpreter.snapshot(!workers.released)

    fun finish(): HotSpotCollectorSnapshot {
        driver.event(HotSpotSessionEvent.Finish(1))
        awaitRetirement(System.nanoTime() + timing.stopNanos)
        val value = snapshot()
        if (value.state !is HotSpotSessionState.Completed || value.pendingOwnership) throw HotSpotSessionFailure(value, this)
        completed.compareAndSet(null, value)
        return checkNotNull(completed.get())
    }

    override fun close() {
        finish()
    }

    private fun awaitReady() {
        try {
            while (interpreter.state !is HotSpotSessionState.Running && interpreter.state !is HotSpotSessionState.Failed) {
                driver.pulse()
                Thread.sleep(POLL_MILLIS)
            }
        } catch (interrupted: InterruptedException) {
            driver.event(HotSpotSessionEvent.Failure(interrupted))
            retireAfterInterrupt()
            Thread.currentThread().interrupt()
        }
        if (interpreter.state is HotSpotSessionState.Failed) {
            if (!Thread.currentThread().isInterrupted) awaitRetirement(System.nanoTime() + timing.stopNanos)
            throw HotSpotSessionFailure(snapshot(), this)
        }
    }

    private fun awaitRetirement(deadline: Long) {
        try {
            workers.tryRetire(retry = interpreter.state is HotSpotSessionState.Failed)
            while (System.nanoTime() - deadline < 0 && !workers.released) {
                driver.pulse()
                workers.tryRetire()
                Thread.sleep(POLL_MILLIS)
            }
            if (workers.released) {
                driver.event(if (interpreter.state is HotSpotSessionState.Failed) HotSpotSessionEvent.CleanupJoined else HotSpotSessionEvent.ResourcesJoined)
            } else {
                driver.event(HotSpotSessionEvent.Failure(HotSpotProtocolException("HotSpot owned cleanup deadline expired")))
            }
        } catch (interrupted: InterruptedException) {
            driver.event(HotSpotSessionEvent.Failure(interrupted))
            retireAfterInterrupt()
            Thread.currentThread().interrupt()
        }
    }

    private fun retireAfterInterrupt() {
        val deadline = System.nanoTime() + timing.stopNanos
        while (System.nanoTime() - deadline < 0 && !workers.released) {
            workers.tryRetire()
            java.util.concurrent.locks.LockSupport
                .parkNanos(INTERRUPTED_POLL_NANOS)
        }
        if (workers.released) driver.event(HotSpotSessionEvent.CleanupJoined)
    }
}
