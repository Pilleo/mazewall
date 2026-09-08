package io.mazewall.profiler.attribution

/** Controls whether Tier E reports every syscall or a unique coverage edge. */
public enum class TierEEmissionMode {
    /** Preserve the complete raw syscall stream. */
    FULL_STREAM,

    /** Emit one representative observation per exact logical stack and syscall number. */
    UNIQUE_STACK_SYSCALL,
}

/** Explicit Tier E session configuration. The full stream remains the safe default. */
public data class TierEOptions(
    val emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
)

/** What the session may truthfully claim about its output. */
public enum class CaptureGuarantee {
    FULL_STREAM,
    UNIQUE_EDGES,
    INCOMPLETE,
}
