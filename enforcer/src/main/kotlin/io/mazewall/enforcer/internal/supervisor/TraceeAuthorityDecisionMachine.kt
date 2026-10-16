package io.mazewall.enforcer.internal.supervisor

/** Reuse the pure supervisor lifecycle for the helper's prepare/decision protocol. */
internal object TraceeAuthorityDecisionMachine {
    fun evaluate(
        prepared: SupervisorRoute,
        decision: SupervisorRoute,
    ): SupervisorRoute {
        val received = SupervisorSessionMachine.evaluate(SupervisorSessionState.AwaitingNotification, SupervisorSessionEvent.NotificationReceived)
        val resolved = SupervisorSessionMachine.evaluate(received.state, SupervisorSessionEvent.PathResolved(prepared))
        val authorized = if (resolved.effect == SupervisorSessionEffect.RequestJvmVerdict) {
            val sent = SupervisorSessionMachine.evaluate(resolved.state, SupervisorSessionEvent.JvmRequestSent)
            SupervisorSessionMachine.evaluate(sent.state, SupervisorSessionEvent.JvmVerdictReceived(decision))
        } else {
            resolved
        }
        return when (val effect = authorized.effect) {
            is SupervisorSessionEffect.ExecuteRoute -> effect.route
            is SupervisorSessionEffect.Abort -> SupervisorRoute.Abort(effect.errno, "invalid authority decision")
            is SupervisorSessionEffect.ProtocolViolation, SupervisorSessionEffect.PostTerminalObservation,
            SupervisorSessionEffect.RequestJvmVerdict, SupervisorSessionEffect.ResolvePath, null,
            ->
                SupervisorRoute.Abort(io.mazewall.ffi.NativeConstants.EPERM, "invalid authority transition")
        }
    }
}
