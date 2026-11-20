package io.mazewall.profiler.internal.hotspot

import io.mazewall.profiler.api.HotSpotObservationTarget

internal sealed interface HotSpotCaptureExpectation {
    val schema: ULong
    val runtimeProfile: String

    data object Strict : HotSpotCaptureExpectation {
        override val schema: ULong = 2uL
        override val runtimeProfile: String = "temurin25_xint_serial"
    }

    data class Observed(
        val target: HotSpotObservationTarget,
    ) : HotSpotCaptureExpectation {
        override val schema: ULong = 3uL
        override val runtimeProfile: String = "otel_hotspot25_observed_v1"
    }
}
