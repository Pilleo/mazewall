package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StacktraceScopingPolicyTest {
    @Test
    fun `default scoping policy always returns true`() {
        val policy = DefaultStacktraceScopingPolicy
        assertTrue(policy.handlers.isEmpty())
    }

    @Test
    fun `custom scoping policy authorizes based on stack trace package`() {
        val policy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.OPENAT to { tid, args, stack ->
                    stack.any { it.className.startsWith("io.mazewall.legit") }
                },
            )
        }

        val legitStack = listOf(
            StackTraceElement("io.mazewall.legit.Workload", "run", "Workload.kt", 10),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 120),
        )
        val maliciousStack = listOf(
            StackTraceElement("io.mazewall.evil.Exploit", "run", "Exploit.kt", 66),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 120),
        )

        assertTrue(policy.handlers[Syscall.OPENAT]?.invoke(Tid(1234), listOf("/etc/hosts"), legitStack) ?: true)
        assertFalse(policy.handlers[Syscall.OPENAT]?.invoke(Tid(1234), listOf("/etc/hosts"), maliciousStack) ?: true)
    }
}
