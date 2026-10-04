package io.mazewall.profiler.internal.hotspot

private const val STARTUP_NANOS = 10_000_000_000L
private const val SESSION_NANOS = 60_000_000_000L
private const val STOP_NANOS = 2_000_000_000L

/** Pure lifecycle decisions. The interpreter owns clock readings, pipes, signals and process leases. */
internal class HotSpotSessionMachine {
    fun evaluate(
        state: HotSpotSessionState,
        event: HotSpotSessionEvent,
        nowNanos: Long,
    ): HotSpotSessionTransition {
        return complete(guard(state, event, nowNanos) ?: dispatch(state, event, nowNanos))
    }

    private fun guard(
        state: HotSpotSessionState,
        event: HotSpotSessionEvent,
        now: Long,
    ): HotSpotSessionTransition? =
        when {
        event is HotSpotSessionEvent.Failure -> fail(state, event.cause)
        state is HotSpotSessionState.Failed -> HotSpotSessionTransition(
            if (event == HotSpotSessionEvent.CleanupJoined) HotSpotSessionState.Failed(state.failures, false) else state,
        )
        state is HotSpotSessionState.Completed -> HotSpotSessionTransition(state)
        // Subtraction is wrap-safe for these finite nanoTime windows; nanoTime need not be positive.
        (state is HotSpotSessionState.Starting && now - state.startupDeadline >= 0) ||
            (state is HotSpotSessionState.Finishing && now - state.deadline >= 0) -> fail(state, HotSpotProtocolException("HotSpot startup/stop deadline expired"))
        else -> null
    }

    private fun dispatch(
        state: HotSpotSessionState,
        event: HotSpotSessionEvent,
        nowNanos: Long,
    ): HotSpotSessionTransition =
        when (event) {
            HotSpotSessionEvent.Start -> if (state == HotSpotSessionState.Created) {
                HotSpotSessionTransition(HotSpotSessionState.Starting(nowNanos + STARTUP_NANOS, nowNanos + SESSION_NANOS), listOf(HotSpotSessionEffect.Launch))
            } else {
                invalid(state)
            }
            is HotSpotSessionEvent.Started -> HotSpotSessionProgress.started(state, event)
            is HotSpotSessionEvent.Ready -> HotSpotSessionProgress.ready(state, event)
            HotSpotSessionEvent.BeginWork -> HotSpotSessionProgress.beginWork(state, nowNanos)
            is HotSpotSessionEvent.Finish -> finish(state, event.owner, nowNanos)
            is HotSpotSessionEvent.Summary -> HotSpotSessionProgress.summary(state, event)
            is HotSpotSessionEvent.ProcessExited -> HotSpotSessionProgress.exited(state, event)
            HotSpotSessionEvent.StdoutEnded -> HotSpotSessionProgress.stdoutEnded(state)
            HotSpotSessionEvent.StderrJoined -> HotSpotSessionProgress.stderrJoined(state)
            HotSpotSessionEvent.CleanupJoined -> invalid(state)
            is HotSpotSessionEvent.Failure -> fail(state, event.cause)
            HotSpotSessionEvent.Tick -> tick(state, nowNanos)
        } ?: invalid(state)

    private fun finish(
        state: HotSpotSessionState,
        owner: Long,
        now: Long,
    ): HotSpotSessionTransition =
        when (state) {
        is HotSpotSessionState.Ready -> beginFinish(state.session, state.hardDeadline, owner, now)
        is HotSpotSessionState.Running -> beginFinish(state.session, state.hardDeadline, owner, now)
        is HotSpotSessionState.Finishing -> HotSpotSessionTransition(state, listOf(HotSpotSessionEffect.AwaitOwner(state.owner)))
        HotSpotSessionState.Created, is HotSpotSessionState.Starting -> fail(state, HotSpotProtocolException("HotSpot startup cancelled"))
        is HotSpotSessionState.Completed, is HotSpotSessionState.Failed -> HotSpotSessionTransition(state)
    }

    private fun beginFinish(
        session: String,
        hardDeadline: Long,
        owner: Long,
        now: Long,
    ): HotSpotSessionTransition {
        require(owner >= 0) { "Invalid finish owner" }
        val remaining = (hardDeadline - now).coerceAtLeast(0).coerceAtMost(STOP_NANOS)
        return HotSpotSessionTransition(HotSpotSessionState.Finishing(session, owner, now + remaining), listOf(HotSpotSessionEffect.RequestStop))
    }

    private fun complete(transition: HotSpotSessionTransition): HotSpotSessionTransition {
        val state = transition.state
        return if (state is HotSpotSessionState.Finishing && state.summaryAccepted && state.stdoutEnded && state.stderrJoined && state.processExited) {
            HotSpotSessionTransition(HotSpotSessionState.Completed(state.session), transition.effects)
        } else {
            transition
        }
    }

    private fun tick(
        state: HotSpotSessionState,
        now: Long,
    ): HotSpotSessionTransition =
        when (state) {
        is HotSpotSessionState.Ready -> if (now - state.hardDeadline >= 0) finish(state, 0, now) else HotSpotSessionTransition(state)
        is HotSpotSessionState.Running -> if (now - state.hardDeadline >= 0) finish(state, 0, now) else HotSpotSessionTransition(state)
        HotSpotSessionState.Created, is HotSpotSessionState.Starting, is HotSpotSessionState.Finishing,
        is HotSpotSessionState.Failed, is HotSpotSessionState.Completed,
        -> HotSpotSessionTransition(state)
    }

    private fun invalid(state: HotSpotSessionState): HotSpotSessionTransition = fail(state, HotSpotProtocolException("Unexpected HotSpot lifecycle event"))

    private fun fail(
        state: HotSpotSessionState,
        cause: Throwable,
    ): HotSpotSessionTransition {
        val failures = if (state is HotSpotSessionState.Failed) state.failures + cause else listOf(cause)
        val retained = when (state) {
            HotSpotSessionState.Created, is HotSpotSessionState.Completed -> false
            is HotSpotSessionState.Failed -> state.retainOwner
            is HotSpotSessionState.Starting, is HotSpotSessionState.Ready, is HotSpotSessionState.Running, is HotSpotSessionState.Finishing -> true
        }
        return HotSpotSessionTransition(HotSpotSessionState.Failed(failures, retained), if (state is HotSpotSessionState.Failed) emptyList() else listOf(HotSpotSessionEffect.Abort))
    }
}
