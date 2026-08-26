package io.mazewall.core

/**
 * Provenance of a [ContextId] attached to a syscall observation.
 *
 * This records **how** a context label came to exist on an event, never whether it is
 * trustworthy. Tier E attribution is tracee-controlled and forgeable by design; consumers
 * must treat every kind as advisory detection data (see the `UNTRUSTED ATTRIBUTION METADATA`
 * contract on [ContextId]).
 */
public enum class AttributionKind {
    /**
     * No semantic context was declared; the observation carries only kernel facts.
     */
    NONE,

    /**
     * The context was declared explicitly by application code through
     * `MazewallContext.withContext { }`.
     */
    EXPLICIT_CONTEXT,

    /**
     * The context was established automatically by the Mazewall java agent around a
     * configured boundary (annotated method, controller, scheduled task).
     */
    AGENT_CONTEXT,

    /**
     * The context was derived by the USER_NOTIF oracle profiler from an exact JVM stack
     * capture taken while the tracee was suspended at the seccomp notification.
     */
    USER_NOTIF_ORACLE,
}
