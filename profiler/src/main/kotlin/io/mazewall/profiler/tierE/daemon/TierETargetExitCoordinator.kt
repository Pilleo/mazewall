package io.mazewall.profiler.tierE.daemon

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
        onTerminalIdentity: () -> Unit,
    ): AutoCloseable {
        val active = AtomicBoolean(true)
        thread(name = "tier-e-target-${target.registrationId}", isDaemon = true) {
            while (active.get()) {
                if (identityVerifier.checkAtTerminal(target) != TierEProcessIdentityCheck.MATCHED) {
                    if (active.compareAndSet(true, false)) onTerminalIdentity()
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
}
