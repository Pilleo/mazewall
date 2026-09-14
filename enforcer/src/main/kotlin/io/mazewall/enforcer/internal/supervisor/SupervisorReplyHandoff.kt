package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.memory.NativeArena

/** Owns an effect until its one-way transfer to the interpreter, or disposal before that transfer. */
internal class SupervisorReplyHandoff(
    effect: SupervisorReplyEffect,
) : AutoCloseable {
    private var pending: SupervisorReplyEffect? = effect

    /** The interpreter consumes the effect's descriptors even when execution or cleanup throws. */
    context(arena: NativeArena) fun transferTo(
        interpreter: SupervisorReplyInterpreter,
        context: SupervisorReplyContext,
    ): ReplyExecutionResult {
        val effect = checkNotNull(pending) { "Supervisor reply effect already transferred or disposed" }
        pending = null
        return interpreter.execute(effect, context)
    }

    override fun close() {
        val effect = pending ?: return
        // Retire before attempting disposal: a failing close must not cause a second attempt.
        pending = null
        when (effect) {
            is SupervisorReplyEffect.AddFdAndReply -> effect.descriptor.close()
            is SupervisorReplyEffect.AcceptedSocket -> effect.descriptor.close()
            is SupervisorReplyEffect.SecureExecAndReply ->
                when (val target = effect.target) {
                    is SupervisorDescriptorResult.Opened -> target.descriptor.close()
                    is SupervisorDescriptorResult.Denied -> Unit
                }
            SupervisorReplyEffect.Continue, is SupervisorReplyEffect.Value, is SupervisorReplyEffect.Error, is SupervisorReplyEffect.Recycle,
            is SupervisorReplyEffect.UnsupportedAuthority,
            -> Unit
        }
    }
}
