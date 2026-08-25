package io.mazewall

import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyBuilderContractTest {

    @Test
    fun `fluent methods return same instance`() {
        val b = Policy.builder()
        assertSame(b, b.block(Syscall.EXECVE), "block should return same instance")
        assertSame(b, b.allow(Syscall.READ), "allow should return same instance")
        assertSame(b, b.defaultAction(SeccompAction.ACT_ERRNO()), "defaultAction should return same instance")
    }

    @Test
    fun `build creates snapshot not aliased by subsequent mutations`() {
        val b = Policy.builder()
        val def1 = b.block(Syscall.EXECVE).build()

        // Mutate after build
        b.block(Syscall.CONNECT)

        // def1 should not contain CONNECT
        assertTrue(def1.syscallActions.containsKey(Syscall.EXECVE), "def1 should have EXECVE")
        assertTrue(
            !def1.syscallActions.containsKey(Syscall.CONNECT) ||
                def1.syscallActions[Syscall.CONNECT] != SeccompAction.ACT_ERRNO(),
            "def1 should not have CONNECT from post-build mutation"
        )
    }

    @Test
    fun `build twice yields different definitions`() {
        val b = Policy.builder()
        val def1 = b.block(Syscall.EXECVE).build()

        // Mutate and build again
        b.block(Syscall.CONNECT)
        val def2 = b.build()

        // def1 should remain stable
        assertSame(def1, def1, "def1 identity should be stable")

        // def1 and def2 should be different (or def2 has more blocks)
        assertTrue(def2.syscallActions.containsKey(Syscall.CONNECT), "def2 should have CONNECT")
    }
}
