package io.mazewall.orchestrator

/**
 * Human-readable backlog ranking. Numeric 0–10 was ambiguous (10 was highest).
 */
public enum class BacklogPriority {
    HIGH,
    MEDIUM,
    LOW,
    ;

    /** Sort key: HIGH first. */
    public val rank: Int
        get() =
            when (this) {
                HIGH -> 2
                MEDIUM -> 1
                LOW -> 0
            }

    public companion object {
        public fun parse(raw: String?): BacklogPriority {
            val token = raw?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()?.uppercase()
            return when (token) {
                "HIGH" -> HIGH
                "MEDIUM" -> MEDIUM
                "LOW" -> LOW
                else ->
                    throw IllegalArgumentException(
                        "Frontmatter 'priority' must be high, medium, or low (got '$raw')",
                    )
            }
        }
    }
}
