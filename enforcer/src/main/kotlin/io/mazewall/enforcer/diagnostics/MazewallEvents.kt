package io.mazewall.enforcer.diagnostics

import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

/**
 * Operator-facing diagnostics event SPI (issue-20260823-172005).
 *
 * Emits structured lifecycle/security events at decision points that are otherwise log-only.
 * Listeners must be fast and non-blocking: they run inline on security-critical threads
 * (installation, daemon supervision). Listener exceptions are isolated so an observability
 * failure cannot alter enforcement; errors still propagate because they signal a failed invariant
 * or an unrecoverable runtime condition.
 *
 * Default sink remains java.util.logging; wire this SPI into Micrometer/OTel as needed.
 */
object MazewallEvents {
    private val logger = Logger.getLogger(MazewallEvents::class.java.name)

    /** Base type for all emitted events. */
    sealed interface Event {
        val timestampMillis: Long
    }

    data class DaemonExited(
        val pid: Long,
        val exitCode: Int,
        val lastLogLines: List<String>,
    ) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class FallbackEngaged(
        val behaviorName: String,
        val reason: String,
    ) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class LandlockApplied(
        val processWide: Boolean,
        val abiVersion: Int,
    ) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class CetOutcome(
        val armed: Boolean,
        val detail: String,
    ) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class SelfVerificationResult(
        val passed: Boolean,
        val detail: String,
    ) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    /**
     * When true, listener exceptions propagate instead of being isolated. Operator/test seam:
     * leave false in production — observability must never alter enforcement. Errors always
     * propagate.
     */
    @Volatile
    var failOnListenerError: Boolean = false

    private val listeners = CopyOnWriteArrayList<DiagnosticEventListener>()

    /** Registers a listener. No-op if already registered. */
    fun register(listener: DiagnosticEventListener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: DiagnosticEventListener) {
        listeners.remove(listener)
    }

    fun registeredCount(): Int = listeners.size

    /**
     * Emits [event] to every listener. Listener exceptions are isolated unless
     * [failOnListenerError] was enabled by the operator (test seam). Errors always propagate.
     */
    fun emit(event: Event) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (expectedListenerFailure: Exception) {
                if (failOnListenerError) throw expectedListenerFailure
                logger.log(java.util.logging.Level.WARNING, "MazewallEvents listener threw", expectedListenerFailure)
            }
        }
    }

    /** Test seam. */
    fun clear() = listeners.clear()
}

/** Functional SAM for Kotlin/Java listeners. */
fun interface DiagnosticEventListener {
    fun onEvent(event: MazewallEvents.Event)
}
