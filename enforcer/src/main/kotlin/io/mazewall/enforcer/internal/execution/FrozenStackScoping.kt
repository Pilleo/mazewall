package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.ffi.internal.OwnedStackScopingBootstrap

internal sealed interface FrozenStackScoping {
    class Legacy(
        policy: StacktraceScopingPolicy,
    ) : FrozenStackScoping {
        private val captured = java.util.Map.copyOf(policy.handlers)
        val policy: StacktraceScopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = captured
        }
    }

    class Live(
        val bootstrap: OwnedStackScopingBootstrap,
    ) : FrozenStackScoping
}
