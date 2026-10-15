package io.mazewall.profiler.api

/** Private collector evidence; unknown destinations never become a typed Connect observation. */
internal sealed interface ConnectResolution {
    data object NotApplicable : ConnectResolution

    data class Resolved(
        val endpoint: NetworkEndpoint,
    ) : ConnectResolution

    data class Failed(
        val reason: Reason,
        val detail: String,
    ) : ConnectResolution

    enum class Reason { NOT_CAPTURED, INVALID_ARGUMENTS, TRUNCATED, UNSUPPORTED_FAMILY, MEMORY_READ_FAILED }
}

internal fun legacyConnectResolution(name: String): ConnectResolution =
    if (name.equals("CONNECT", ignoreCase = true)) {
        ConnectResolution.Failed(ConnectResolution.Reason.NOT_CAPTURED, "CONNECT destination was not captured")
    } else {
        ConnectResolution.NotApplicable
    }
