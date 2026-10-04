package io.mazewall.profiler.internal.hotspot

internal sealed interface HotSpotSessionState {
    data object Created : HotSpotSessionState

    data class Starting(
        val startupDeadline: Long,
        val hardDeadline: Long,
        val session: String? = null,
    ) : HotSpotSessionState

    data class Ready(
        val session: String,
        val hardDeadline: Long,
    ) : HotSpotSessionState

    data class Running(
        val session: String,
        val hardDeadline: Long,
    ) : HotSpotSessionState

    data class Finishing(
        val session: String,
        val owner: Long,
        val deadline: Long,
        val summaryAccepted: Boolean = false,
        val stdoutEnded: Boolean = false,
        val stderrJoined: Boolean = false,
        val processExited: Boolean = false,
    ) : HotSpotSessionState

    class Failed(
        failures: List<Throwable>,
        val retainOwner: Boolean,
    ) : HotSpotSessionState {
        val failures: List<Throwable> = java.util.List.copyOf(failures)
    }

    data class Completed(
        val session: String,
    ) : HotSpotSessionState
}

internal sealed interface HotSpotSessionEvent {
    data object Start : HotSpotSessionEvent

    data class Started(
        val session: String,
    ) : HotSpotSessionEvent

    data class Ready(
        val session: String,
    ) : HotSpotSessionEvent

    data object BeginWork : HotSpotSessionEvent

    data class Finish(
        val owner: Long,
    ) : HotSpotSessionEvent

    /** Healthy means verified pairing/accounting, no native errors, and all required native joins/drains. */
    data class Summary(
        val session: String,
        val healthy: Boolean,
    ) : HotSpotSessionEvent

    data class ProcessExited(
        val code: Int,
    ) : HotSpotSessionEvent

    data object StdoutEnded : HotSpotSessionEvent

    data object StderrJoined : HotSpotSessionEvent

    data object CleanupJoined : HotSpotSessionEvent

    data class Failure(
        val cause: Throwable,
    ) : HotSpotSessionEvent

    data object Tick : HotSpotSessionEvent
}

internal sealed interface HotSpotSessionEffect {
    data object Launch : HotSpotSessionEffect

    data object PublishReady : HotSpotSessionEffect

    data object RunWork : HotSpotSessionEffect

    data object RequestStop : HotSpotSessionEffect

    data object Abort : HotSpotSessionEffect

    data class AwaitOwner(
        val owner: Long,
    ) : HotSpotSessionEffect
}

internal class HotSpotSessionTransition(
    val state: HotSpotSessionState,
    effects: List<HotSpotSessionEffect> = emptyList(),
) {
    val effects: List<HotSpotSessionEffect> = java.util.List.copyOf(effects)
}
