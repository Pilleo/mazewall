package io.mazewall.portal

import io.mazewall.core.CleanupFailures

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
            primary = CleanupFailures.merge(primary, failure.cause)
        }
        return primary
    }
}

internal data class CleanupFailure(
    val resource: String,
    val cause: Throwable,
)
