package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.internal.execution.FrozenStackScoping
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FrozenStackScopingTest {
    @Test
    fun `legacy augmentation uses frozen handlers without mutating original policy`() {
        val handlers = mutableMapOf<Syscall, ScopingHandler>(Syscall.OPENAT to { _, _, _ -> true })
        val scoping = object : StacktraceScopingPolicy {
            override val handlers = handlers
        }
        val frozen = FrozenStackScoping.Legacy(scoping)
        handlers.clear()
        val original = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build().definition
        val augmented = ContainedExecutors.augmentForScoping(original, frozen)
        assertEquals(SeccompAction.ACT_NOTIFY, augmented.syscallActions[Syscall.OPENAT])
        assertEquals(original.argumentRules, augmented.argumentRules)
        assertEquals(null, original.syscallActions[Syscall.OPENAT])
    }
}
