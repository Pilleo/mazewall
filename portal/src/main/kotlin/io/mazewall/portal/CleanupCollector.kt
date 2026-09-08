package io.mazewall.portal

/** Collects best-effort resource-release failures without skipping later owned resources. */
internal class CleanupCollector {
    private val failures = mutableListOf<CleanupFailure>()

    @Suppress("TooGenericExceptionCaught") // cleanup must collect every close failure regardless of implementation type
    fun attempt(
        resource: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (cause: Exception) {
            failures += CleanupFailure(resource, cause)
        }
    }

    fun outcome(): CleanupOutcome = CleanupOutcome(failures.toList())
}

internal data class CleanupOutcome(
    val failures: List<CleanupFailure>,
) {
    val complete: Boolean get() = failures.isEmpty()
}

internal data class CleanupFailure(
    val resource: String,
    val cause: Exception,
)
