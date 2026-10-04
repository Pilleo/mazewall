package io.mazewall.profiler.internal.hotspot

/** Pure, focused admission/join transitions used only by the exhaustive session dispatcher. */
internal object HotSpotSessionProgress {
    fun started(
        state: HotSpotSessionState,
        event: HotSpotSessionEvent.Started,
    ): HotSpotSessionTransition? =
        if (state is HotSpotSessionState.Starting && state.session == null && event.session.isNotEmpty()) {
            HotSpotSessionTransition(state.copy(session = event.session))
        } else {
            null
        }

    fun ready(
        state: HotSpotSessionState,
        event: HotSpotSessionEvent.Ready,
    ): HotSpotSessionTransition? =
        if (state is HotSpotSessionState.Starting && state.session == event.session) {
            HotSpotSessionTransition(HotSpotSessionState.Ready(event.session, state.hardDeadline), listOf(HotSpotSessionEffect.PublishReady))
        } else {
            null
        }

    fun beginWork(
        state: HotSpotSessionState,
        now: Long,
    ): HotSpotSessionTransition? =
        if (state is HotSpotSessionState.Ready && now - state.hardDeadline < 0) {
            HotSpotSessionTransition(HotSpotSessionState.Running(state.session, state.hardDeadline), listOf(HotSpotSessionEffect.RunWork))
        } else {
            null
        }

    fun summary(
        state: HotSpotSessionState,
        event: HotSpotSessionEvent.Summary,
    ): HotSpotSessionTransition? =
        if (state is HotSpotSessionState.Finishing && !state.summaryAccepted && state.session == event.session && event.healthy) {
            HotSpotSessionTransition(state.copy(summaryAccepted = true))
        } else {
            null
        }

    fun exited(
        state: HotSpotSessionState,
        event: HotSpotSessionEvent.ProcessExited,
    ): HotSpotSessionTransition? =
        if (state is HotSpotSessionState.Finishing && !state.processExited && event.code == 0) {
            HotSpotSessionTransition(state.copy(processExited = true))
        } else {
            null
        }

    fun stdoutEnded(state: HotSpotSessionState): HotSpotSessionTransition? =
        if (state is HotSpotSessionState.Finishing && state.summaryAccepted && !state.stdoutEnded) {
            HotSpotSessionTransition(state.copy(stdoutEnded = true))
        } else {
            null
        }

    fun stderrJoined(state: HotSpotSessionState): HotSpotSessionTransition? =
        if (state is HotSpotSessionState.Finishing && !state.stderrJoined) {
            HotSpotSessionTransition(state.copy(stderrJoined = true))
        } else {
            null
        }
}
