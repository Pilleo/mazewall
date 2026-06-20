package io.mazewall.enforcer.supervisor

import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StacktraceScopingPolicyTest {

    @Test
    fun `default scoping policy always returns true`() {
        val policy = DefaultStacktraceScopingPolicy
        val result = policy.authorize(
            Tid(1234),
            Syscall.OPENAT,
            listOf("/etc/hosts"),
            emptyList()
        )
        assertTrue(result)
    }

    @Test
    fun `custom scoping policy authorizes based on stack trace package`() {
        val policy = object : StacktraceScopingPolicy {
            override fun authorize(
                tid: Tid,
                syscall: Syscall,
                args: List<Any>,
                stack: List<StackTraceElement>
            ): Boolean {
                return stack.any { it.className.startsWith("io.mazewall.legit") }
            }
        }

        val legitStack = listOf(
            StackTraceElement("io.mazewall.legit.Workload", "run", "Workload.kt", 10),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 120)
        )
        val maliciousStack = listOf(
            StackTraceElement("io.mazewall.evil.Exploit", "run", "Exploit.kt", 66),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 120)
        )

        assertTrue(policy.authorize(Tid(1234), Syscall.OPENAT, listOf("/etc/hosts"), legitStack))
        assertFalse(policy.authorize(Tid(1234), Syscall.OPENAT, listOf("/etc/hosts"), maliciousStack))
    }
}
