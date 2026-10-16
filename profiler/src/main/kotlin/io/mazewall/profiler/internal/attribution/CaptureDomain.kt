package io.mazewall.profiler.internal.attribution

/** Machine-readable distinction between observed events and attempts outside the hook domain. */
internal enum class CaptureDomainClassification(
    public val wireName: String,
) {
    OBSERVED_SYS_ENTER("observed_sys_enter"),
    ATTEMPT_MISSED("attempt_missed"),
}

internal data class CaptureDomainEvent(
    public val classification: CaptureDomainClassification,
    public val count: Long = 1,
) {
    init {
        require(count > 0) { "capture-domain event count must be positive" }
    }
}
