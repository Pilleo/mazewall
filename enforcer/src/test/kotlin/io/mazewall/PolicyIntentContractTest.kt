package io.mazewall.enforcer.api

import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyIntentContractTest {
    @Test
    fun `active empty filesystem intersection remains active after template reconstruction`() {
        val left = Policy.threadLocalBuilder().allowFsRead("/input/a").build()
        val right = Policy.threadLocalBuilder().allowFsRead("/input/b").build()
        val intersection = PolicyDefinition.intersection(left.definition, right.definition)

        assertTrue(intersection.enforceLandlock)
        assertTrue(intersection.allowedFsReadPaths.isEmpty())

        val rebuilt = PolicyBuilder<PolicyScope.ThreadLocalOnly>().base(intersection).build()

        assertTrue(rebuilt.enforceLandlock, "A deny-all template must not become unrestricted")
        assertTrue(rebuilt.allowedFsReadPaths.isEmpty())
        assertTrue(rebuilt.allowedFsWritePaths.isEmpty())
        assertEquals(SeccompAction.ACT_ALLOW, rebuilt.syscallActions[Syscall.OPENAT])
    }

    @Test
    fun `fresh empty filesystem construction remains explicitly unrestricted`() {
        val policy = PolicyBuilder<PolicyScope.ThreadLocalOnly>().build()
        assertFalse(policy.enforceLandlock)
        assertTrue(policy.allowedFsReadPaths.isEmpty())
        assertTrue(policy.allowedFsWritePaths.isEmpty())
    }

    @Test
    fun `built syscall permissions cannot be changed through a mutable collection view`() {
        val definition = PolicyBuilder<PolicyScope.ThreadLocalOnly>()
            .block(Syscall.EXECVE)
            .build()

        assertThrows(UnsupportedOperationException::class.java) {
            (definition.syscallActions as MutableMap<Syscall, SeccompAction>).clear()
        }
        assertFalse(definition.isSyscallAllowed(Syscall.EXECVE))
    }

    @Test
    fun `built filesystem permissions cannot be changed through a mutable collection view`() {
        val definition = PolicyBuilder<PolicyScope.ThreadLocalOnly>()
            .allowFsRead("/input/a")
            .allowFsRead("/input/b")
            .build()

        assertThrows(UnsupportedOperationException::class.java) {
            (definition.allowedFsReadPaths as MutableSet<SandboxedPath>).clear()
        }
        assertEquals(2, definition.allowedFsReadPaths.size)
        assertTrue(definition.enforceLandlock)
    }

    @Test
    fun `composed syscall permissions cannot be changed through a mutable collection view`() {
        val first = PolicyBuilder<PolicyScope.ThreadLocalOnly>().block(Syscall.EXECVE).build()
        val second = PolicyBuilder<PolicyScope.ThreadLocalOnly>().block(Syscall.CONNECT).build()
        val composed = PolicyDefinition.intersection(first, second)

        assertThrows(UnsupportedOperationException::class.java) {
            (composed.syscallActions as MutableMap<Syscall, SeccompAction>).clear()
        }
        assertFalse(composed.isSyscallAllowed(Syscall.EXECVE))
        assertFalse(composed.isSyscallAllowed(Syscall.CONNECT))
    }
}
