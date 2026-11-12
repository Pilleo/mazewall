package io.mazewall.enforcer.internal.supervisor

/** Validated startup-only budget; absence retains the existing five-second policy. */
internal object SupervisorPrewarmBudget {
    const val PROPERTY = "mazewall.supervisor.prewarmTimeoutMillis"
    private const val DEFAULT_MILLIS = 5_000L
    private const val MAX_MILLIS = 15_000L

    fun parse(raw: String?): Long {
        if (raw == null) return DEFAULT_MILLIS
        require(raw.isNotEmpty() && raw.all { it in '0'..'9' }) { "$PROPERTY must be decimal milliseconds from 1 to $MAX_MILLIS" }
        val millis = raw.toLongOrNull()
        require(millis != null && millis in 1..MAX_MILLIS) { "$PROPERTY must be decimal milliseconds from 1 to $MAX_MILLIS" }
        return millis
    }

    fun childArguments(raw: String?): List<String> = if (raw == null) emptyList() else listOf("-D$PROPERTY=${parse(raw)}")
}
