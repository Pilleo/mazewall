package io.mazewall.ffi.networking

import io.mazewall.platform.lifecycle.Deadline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class SeccompInstallStage { Prepared, Applied, Transferred, Ready }

internal sealed interface SeccompInstallState {
    val stage: SeccompInstallStage

    data class Pending(
        override val stage: SeccompInstallStage,
    ) : SeccompInstallState

    data class Failed(
        override val stage: SeccompInstallStage,
        val cause: Throwable,
    ) : SeccompInstallState
}

internal sealed interface SeccompInstallEvent {
    data class Advanced(
        val stage: SeccompInstallStage,
    ) : SeccompInstallEvent

    data class Failed(
        val cause: Throwable,
    ) : SeccompInstallEvent
}

internal enum class SeccompInstallEffect { Wait, Release }

internal data class SeccompInstallTransition(
    val state: SeccompInstallState,
    val effect: SeccompInstallEffect,
)

/** Failure is absorbing, but a late irreversible kernel result still advances its recorded stage. */
internal object SeccompInstallMachine {
    fun evaluate(
        state: SeccompInstallState,
        event: SeccompInstallEvent,
    ): SeccompInstallTransition {
        val next = when (event) {
            is SeccompInstallEvent.Failed -> when (state) {
                is SeccompInstallState.Pending -> SeccompInstallState.Failed(state.stage, event.cause)
                is SeccompInstallState.Failed -> state
            }
            is SeccompInstallEvent.Advanced -> {
                val stage = maxOf(state.stage, event.stage)
                when (state) {
                    is SeccompInstallState.Pending -> SeccompInstallState.Pending(stage)
                    is SeccompInstallState.Failed -> state.copy(stage = stage)
                }
            }
        }
        val release = next is SeccompInstallState.Failed || next.stage == SeccompInstallStage.Ready
        return SeccompInstallTransition(next, if (release) SeccompInstallEffect.Release else SeccompInstallEffect.Wait)
    }
}

/** One monotonic budget for every latch; native resource ownership stays with the coordinator. */
internal class SeccompInstallHandshake(
    private val onFilterApplied: () -> Unit,
    private val deadline: Deadline = configuredDeadline(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    val installed = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    private val publicationLock = Any()
    private var appliedPublished = false
    private var publicationFailure: Throwable? = null
    private val timeout = IllegalStateException("Seccomp installation handshake timed out")
    private var state: SeccompInstallState = SeccompInstallState.Pending(SeccompInstallStage.Prepared)

    init {
        // Initialize reducer result/event classes on the uncontained caller, before NEW_LISTENER.
        val warm = SeccompInstallMachine.evaluate(state, SeccompInstallEvent.Advanced(SeccompInstallStage.Ready))
        SeccompInstallMachine.evaluate(warm.state, SeccompInstallEvent.Failed(timeout))
        // Exercise the interpreter too: Kotlin's synthetic enum switch class must not
        // be loaded via a supervised open while this object's monitor is held.
        advance(SeccompInstallStage.Prepared)
        TimeUnit.NANOSECONDS.toMillis(1)
    }

    @Synchronized
    fun advance(stage: SeccompInstallStage) = dispatch(SeccompInstallEvent.Advanced(stage))

    @Synchronized
    fun fail(cause: Throwable) = dispatch(SeccompInstallEvent.Failed(cause))

    private fun dispatch(event: SeccompInstallEvent) {
        val transition = SeccompInstallMachine.evaluate(state, event)
        state = transition.state
        when (transition.effect) {
            SeccompInstallEffect.Wait -> Unit
            SeccompInstallEffect.Release -> proceed.countDown()
        }
    }

    /**
     * A claim is not completion: timeout cleanup must join an already-running publisher.
     * Keep this lock separate so publication cannot prevent the deadline recording failure.
     * The callback must be short and nonblocking; its completion is required even after timeout.
     */
    @Suppress("TooGenericExceptionCaught") // Every publisher observes the same callback result, including fatal errors.
    fun publishApplied() {
        val applied = synchronized(this) { state.stage >= SeccompInstallStage.Applied }
        if (!applied) return
        synchronized(publicationLock) {
            if (!appliedPublished) {
                try {
                    onFilterApplied()
                } catch (failure: Throwable) {
                    publicationFailure = failure
                } finally {
                    appliedPublished = true
                }
            }
            publicationFailure?.let { throw it }
        }
    }

    fun remainingMillis(): Int {
        checkActive()
        return deadline.remainingMillis(nanoTime()).coerceAtLeast(1)
    }

    @Synchronized
    fun checkActive() {
        when (val current = state) {
            is SeccompInstallState.Failed -> throw current.cause
            is SeccompInstallState.Pending -> if (deadline.nanoTime - nanoTime() <= 0) {
                fail(timeout)
                throw timeout
            }
        }
    }

    /** Interruptions never renew the budget and are restored after the wait. */
    fun await(latch: CountDownLatch) {
        var interrupted = false
        try {
            while (true) {
                checkActive()
                val remaining = deadline.nanoTime - nanoTime()
                if (remaining <= 0) {
                    fail(timeout)
                    throw timeout
                }
                try {
                    if (latch.await(remaining, TimeUnit.NANOSECONDS)) {
                        checkActive()
                        return
                    }
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        fun configuredDeadline(): Deadline {
            val raw = System.getProperty("mazewall.supervisor.installTimeoutMillis")
            val duration = if (raw == null) {
                30_000L
            } else {
                requireNotNull(raw.toLongOrNull()?.takeIf { it in 1..30_000 }) {
                    "mazewall.supervisor.installTimeoutMillis must be in 1..30000"
                }
            }
            return Deadline.afterMillis(duration)
        }
    }
}
