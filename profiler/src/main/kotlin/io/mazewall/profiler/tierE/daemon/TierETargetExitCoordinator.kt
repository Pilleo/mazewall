package io.mazewall.profiler.tierE.daemon

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal sealed interface TierETargetExit {
    data object IdentityChanged : TierETargetExit

    data class ObservationFailed(
        val cause: Throwable,
    ) : TierETargetExit
}

/** Watches one registered host PID without coupling Tier E to a container runtime. */
internal class TierETargetExitCoordinator(
    private val identityVerifier: TierEProcessIdentityVerifier,
    private val pollIntervalMillis: Long = 100L,
) {
    init {
        require(pollIntervalMillis > 0) { "poll interval must be positive" }
    }

    fun watch(
        target: TierERegisteredTarget,
        onTerminal: (TierETargetExit) -> Unit,
    ): AutoCloseable {
        val active = AtomicBoolean(true)
        thread(name = "tier-e-target-${target.registrationId}", isDaemon = true) {
            while (active.get()) {
                val outcome = observeExit(target)
                if (outcome != null) {
                    if (active.compareAndSet(true, false)) {
                        onTerminal(outcome)
                    } else if (outcome is TierETargetExit.ObservationFailed && outcome.cause is Error) {
                        throw outcome.cause
                    }
                    return@thread
                }
                try {
                    Thread.sleep(pollIntervalMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
            }
        }
        return AutoCloseable { active.set(false) }
    }

    @Suppress("TooGenericExceptionCaught") // Report observation failure to the owner responsible for retirement.
    private fun observeExit(target: TierERegisteredTarget): TierETargetExit? {
        return try {
            when (identityVerifier.checkAtTerminal(target)) {
                TierEProcessIdentityCheck.MATCHED -> null
                TierEProcessIdentityCheck.EXITED, TierEProcessIdentityCheck.REUSED -> TierETargetExit.IdentityChanged
            }
        } catch (cause: Throwable) {
            TierETargetExit.ObservationFailed(cause)
        }
    }
}
