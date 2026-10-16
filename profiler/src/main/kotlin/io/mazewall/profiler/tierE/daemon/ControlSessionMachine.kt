package io.mazewall.profiler.tierE.daemon

internal data class AttachedSessionInfo(
    public val pid: Int,
    public val sessionTag: Int,
    /** Process incarnation epoch to detect PID reuse across exec/exit transitions. */
    public val processIncarnation: Long = 0L,
)

internal sealed interface ControlSessionState {
    public data object Idle : ControlSessionState

    public data class Attaching(
        public val command: ControlCommand.Attach,
    ) : ControlSessionState

    public data class Attached(
        public val session: AttachedSessionInfo,
    ) : ControlSessionState

    public data object Terminated : ControlSessionState
}

internal sealed interface ControlSessionEvent {
    public data class CommandReceived(
        public val command: ControlCommand,
    ) : ControlSessionEvent

    public data class SyntaxError(
        public val reply: ControlReply,
    ) : ControlSessionEvent

    public data class AttachSucceeded(
        public val session: AttachedSessionInfo,
    ) : ControlSessionEvent

    public data class AttachFailed(
        public val error: String,
    ) : ControlSessionEvent
}

internal sealed interface ControlSessionEffect {
    public data class SendReply(
        public val reply: ControlReply,
    ) : ControlSessionEffect

    public data class RespondStatus(
        public val attachedSession: AttachedSessionInfo?,
    ) : ControlSessionEffect

    public data class PerformAttach(
        public val command: ControlCommand.Attach,
        public val previousSession: AttachedSessionInfo?,
    ) : ControlSessionEffect

    public data class CloseSessionAndReply(
        public val session: AttachedSessionInfo,
        public val reply: ControlReply,
    ) : ControlSessionEffect

    public data class CloseAndShutdown(
        public val session: AttachedSessionInfo?,
    ) : ControlSessionEffect
}

internal data class ControlSessionTransition(
    public val state: ControlSessionState,
    public val effect: ControlSessionEffect,
)

internal object ControlSessionMachine {
    public fun evaluate(
        state: ControlSessionState,
        event: ControlSessionEvent,
    ): ControlSessionTransition =
        when (event) {
            is ControlSessionEvent.SyntaxError -> ControlSessionTransition(
                state = state,
                effect = ControlSessionEffect.SendReply(event.reply),
            )

            is ControlSessionEvent.AttachSucceeded -> when (state) {
                is ControlSessionState.Attaching,
                ControlSessionState.Idle,
                is ControlSessionState.Attached,
                ControlSessionState.Terminated,
                -> ControlSessionTransition(
                    state = ControlSessionState.Attached(event.session),
                    effect = ControlSessionEffect.SendReply(ControlReply.ok("attached pid=${event.session.pid}")),
                )
            }

            is ControlSessionEvent.AttachFailed -> ControlSessionTransition(
                state = ControlSessionState.Idle,
                effect = ControlSessionEffect.SendReply(ControlReply.err(event.error)),
            )

            is ControlSessionEvent.CommandReceived -> handleCommand(state, event.command)
        }

    private fun handleCommand(
        state: ControlSessionState,
        command: ControlCommand,
    ): ControlSessionTransition =
        if (state == ControlSessionState.Terminated) {
            ControlSessionTransition(
                state = ControlSessionState.Terminated,
                effect = ControlSessionEffect.SendReply(ControlReply.err("STATE")),
            )
        } else {
            when (command) {
            is ControlCommand.Attach -> {
                val previous = (state as? ControlSessionState.Attached)?.session
                ControlSessionTransition(
                    state = ControlSessionState.Attaching(command),
                    effect = ControlSessionEffect.PerformAttach(command, previous),
                )
            }

            is ControlCommand.Detach -> when (state) {
                is ControlSessionState.Attached -> ControlSessionTransition(
                    state = ControlSessionState.Idle,
                    effect = ControlSessionEffect.CloseSessionAndReply(state.session, ControlReply.ok("detached")),
                )
                ControlSessionState.Idle,
                is ControlSessionState.Attaching,
                ControlSessionState.Terminated,
                -> ControlSessionTransition(
                    state = ControlSessionState.Idle,
                    effect = ControlSessionEffect.SendReply(ControlReply.ok("detached")),
                )
            }

            is ControlCommand.Status -> {
                val session = (state as? ControlSessionState.Attached)?.session
                ControlSessionTransition(
                    state = state,
                    effect = ControlSessionEffect.RespondStatus(session),
                )
            }

            is ControlCommand.Shutdown -> {
                val session = (state as? ControlSessionState.Attached)?.session
                ControlSessionTransition(
                    state = ControlSessionState.Terminated,
                    effect = ControlSessionEffect.CloseAndShutdown(session),
                )
            }

            is ControlCommand.Unknown -> ControlSessionTransition(
                state = state,
                effect = ControlSessionEffect.SendReply(
                    command.rejectionReply() ?: ControlReply.err("unknown command"),
                ),
            )
        }
        }
}
