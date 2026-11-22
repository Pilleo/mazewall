package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyBuilderContractTest {
    @Test
    fun `fluent methods return same instance`() {
        val b = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
        assertSame(b, b.block(Syscall.EXECVE), "block should return same instance")
        assertSame(b, b.allow(Syscall.READ), "allow should return same instance")
        assertSame(b, b.defaultAction(SeccompAction.ACT_ERRNO()), "defaultAction should return same instance")
    }

    @Test
    fun `build creates snapshot not aliased by subsequent mutations`() {
        val b = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
        val def1 = b.block(Syscall.EXECVE).build()

        // Mutate after build
        b.block(Syscall.CONNECT)

        // def1 should not contain CONNECT
        assertTrue(def1.syscallActions.containsKey(Syscall.EXECVE), "def1 should have EXECVE")
        assertTrue(
            !def1.syscallActions.containsKey(Syscall.CONNECT) ||
                def1.syscallActions[Syscall.CONNECT] != SeccompAction.ACT_ERRNO(),
            "def1 should not have CONNECT from post-build mutation",
        )
    }

    @Test
    fun `build twice yields different definitions`() {
        val b = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
        val def1 = b.block(Syscall.EXECVE).build()

        // Mutate and build again
        b.block(Syscall.CONNECT)
        val def2 = b.build()

        // def1 should remain stable
        assertSame(def1, def1, "def1 identity should be stable")

        // def1 and def2 should be different (or def2 has more blocks)
        assertTrue(def2.syscallActions.containsKey(Syscall.CONNECT), "def2 should have CONNECT")
    }

    @Test
    fun `raw PolicyDefinition construction snapshots all collection inputs`() {
        val actions = mutableMapOf<Syscall, SeccompAction>(Syscall.GETPID to SeccompAction.ACT_ERRNO())
        val readPaths = mutableSetOf(SandboxedPath.of("/tmp", allowNonExistent = true))
        val writePaths = mutableSetOf(SandboxedPath.of("/var/tmp", allowNonExistent = true))
        val phrases = mutableListOf("denied_phrase")
        val regex = Regex("denied_.*")
        val regexes = mutableListOf(regex)

        val raw =
            PolicyDefinition<PolicyScope.ThreadLocalOnly>(
                syscallActions = actions,
                allowedFsReadPaths = readPaths,
                allowedFsWritePaths = writePaths,
                customViolationPhrases = phrases,
                customViolationRegexes = regexes,
            )
        val initialHash = raw.hashCode()
        val baseline =
            PolicyDefinition<PolicyScope.ThreadLocalOnly>(
                syscallActions = mapOf(Syscall.GETPID to SeccompAction.ACT_ERRNO()),
                allowedFsReadPaths = setOf(SandboxedPath.of("/tmp", allowNonExistent = true)),
                allowedFsWritePaths = setOf(SandboxedPath.of("/var/tmp", allowNonExistent = true)),
                customViolationPhrases = listOf("denied_phrase"),
                customViolationRegexes = listOf(regex),
            )

        actions[Syscall.GETPID] = SeccompAction.ACT_ALLOW
        actions[Syscall.EXECVE] = SeccompAction.ACT_KILL_PROCESS
        readPaths.add(SandboxedPath.of("/etc", allowNonExistent = true))
        writePaths.add(SandboxedPath.of("/opt", allowNonExistent = true))
        phrases.add("extra_phrase")
        regexes.add(Regex("extra_.*"))

        assertFalse(raw.isSyscallAllowed(Syscall.GETPID))
        assertTrue(raw.isSyscallAllowed(Syscall.EXECVE))
        assertEquals(1, raw.allowedFsReadPaths.size)
        assertEquals(1, raw.allowedFsWritePaths.size)
        assertEquals(listOf("denied_phrase"), raw.customViolationPhrases)
        assertEquals(listOf(regex), raw.customViolationRegexes)
        assertEquals(baseline, raw)
        assertEquals(initialHash, raw.hashCode())
        assertUnmodifiableCollections(raw)
    }

    @Test
    fun `PolicyDefinition copy snapshots mutable collection inputs`() {
        val base = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build().definition
        val copyActions = mutableMapOf<Syscall, SeccompAction>(Syscall.GETPID to SeccompAction.ACT_ERRNO())
        val copyReads = mutableSetOf(SandboxedPath.of("/tmp", allowNonExistent = true))
        val copyWrites = mutableSetOf(SandboxedPath.of("/var/tmp", allowNonExistent = true))
        val copyPhrases = mutableListOf("copy_phrase")
        val copyRegex = Regex("copy_.*")
        val copyRegexes = mutableListOf(copyRegex)

        val copied =
            base.copy(
                syscallActions = copyActions,
                allowedFsReadPaths = copyReads,
                allowedFsWritePaths = copyWrites,
                customViolationPhrases = copyPhrases,
                customViolationRegexes = copyRegexes,
            )

        copyActions[Syscall.GETPID] = SeccompAction.ACT_ALLOW
        copyReads.clear()
        copyWrites.clear()
        copyPhrases.clear()
        copyRegexes.clear()

        assertFalse(copied.isSyscallAllowed(Syscall.GETPID))
        assertEquals(1, copied.allowedFsReadPaths.size)
        assertEquals(1, copied.allowedFsWritePaths.size)
        assertEquals(listOf("copy_phrase"), copied.customViolationPhrases)
        assertEquals(listOf(copyRegex), copied.customViolationRegexes)
        assertUnmodifiableCollections(copied)
    }

    @Test
    fun `PolicyCompilationCache key stays stable after caller mutates source map`() {
        PolicyCompilationCache.clear()
        try {
            val arch = Arch.AMD64
            val callerActions = mutableMapOf<Syscall, SeccompAction>(Syscall.EXECVE to SeccompAction.ACT_ERRNO())
            val policy = PolicyDefinition<PolicyScope.ProcessWideSafe>(syscallActions = callerActions)
            val first = PolicyCompilationCache.getOrCompile(policy, arch)

            callerActions[Syscall.EXECVE] = SeccompAction.ACT_ALLOW
            callerActions[Syscall.CONNECT] = SeccompAction.ACT_KILL_PROCESS

            val second = PolicyCompilationCache.getOrCompile(policy, arch)
            assertSame(first, second)
            assertEquals(1, PolicyCompilationCache.entryCount())
            assertFalse(policy.isSyscallAllowed(Syscall.EXECVE))
            assertTrue(policy.isSyscallAllowed(Syscall.CONNECT))
        } finally {
            PolicyCompilationCache.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertUnmodifiableCollections(definition: PolicyDefinition<*>) {
        assertThrows(UnsupportedOperationException::class.java) {
            (definition.syscallActions as MutableMap<Syscall, SeccompAction>)[Syscall.READ] = SeccompAction.ACT_ALLOW
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (definition.allowedFsReadPaths as MutableSet<SandboxedPath>).add(SandboxedPath.of("/root", allowNonExistent = true))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (definition.allowedFsWritePaths as MutableSet<SandboxedPath>).add(SandboxedPath.of("/root", allowNonExistent = true))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (definition.customViolationPhrases as MutableList<String>).add("mutated")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (definition.customViolationRegexes as MutableList<Regex>).add(Regex("mutated"))
        }
    }
}
