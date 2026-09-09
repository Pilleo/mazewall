package io.mazewall.portal

/** Collects best-effort resource-release failures without skipping later owned resources. */
internal class CleanupCollector {
    private val failures = mutableListOf<CleanupFailure>()

    @Suppress("TooGenericExceptionCaught") // cleanup must collect every close failure, including fatal implementations
    fun attempt(
        resource: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (cause: Throwable) {
            failures += CleanupFailure(resource, cause)
        }
    }

    fun outcome(): CleanupOutcome = CleanupOutcome(failures.toList())
}

internal data class CleanupOutcome(
    val failures: List<CleanupFailure>,
) {
    val complete: Boolean get() = failures.isEmpty()

    /** Preserve the first failure unless a later fatal failure must take precedence. */
    fun failureOrNull(): Throwable? {
        var primary: Throwable? = null
        failures.forEach { failure ->
            val next = failure.cause
            val current = primary
            primary = when {
                current == null -> next
                next is Error && current !is Error -> {
                    if (next !== current) next.addSuppressed(current)
                    next
                }
                current !== next -> {
                    current.addSuppressed(next)
                    current
                }
                else -> current
            }
        }
        return primary
    }
}

internal data class CleanupFailure(
    val resource: String,
    val cause: Throwable,
)
