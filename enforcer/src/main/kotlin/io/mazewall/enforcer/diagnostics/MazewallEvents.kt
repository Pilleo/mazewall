package io.mazewall.enforcer.diagnostics

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Operator-facing diagnostics event SPI (issue-20260823-172005).
 *
 * Emits structured lifecycle/security events at decision points that are otherwise log-only.
 * Listeners must be fast and non-blocking: they run inline on security-critical threads
 * (installation, daemon supervision) and exceptions are swallowed by design — an observability
 * failure must never alter enforcement behavior.
 *
 * Default sink remains java.util.logging; wire this SPI into Micrometer/OTel as needed.
 */
object MazewallEvents {
    /** Base type for all emitted events. */
    sealed interface Event {
        val timestampMillis: Long
    }

    data class DaemonExited(val pid: Long, val exitCode: Int, val lastLogLines: List<String>) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class FallbackEngaged(val behaviorName: String, val reason: String) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class LandlockApplied(val processWide: Boolean, val abiVersion: Int) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class CetOutcome(val armed: Boolean, val detail: String) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    data class SelfVerificationResult(val passed: Boolean, val detail: String) : Event {
        override val timestampMillis: Long = System.currentTimeMillis()
    }

    /**
     * When true, listener exceptions propagate instead of being swallowed. Operator/test seam:
     * leave false in production — observability must never alter enforcement.
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
     * Emits [event] to every listener. Listener exceptions are swallowed unless
     * [failOnListenerError] was enabled by the operator (test seam).
     */
    fun emit(event: Event) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (t: Throwable) {
                if (failOnListenerError) throw t
                System.err.println("WARNING: MazewallEvents listener threw: $t")
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
