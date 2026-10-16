package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression tests for issue-20260823-171953: the compilation cache key must be the
 * program-relevant projection of a PolicyDefinition. FS paths never influence BPF output, so
 * definitions differing only in paths must share ONE cached instance; distinct syscall behavior
 * must compile distinctly. Assertions use instance identity, which stays valid even when other
 * tests' executor threads insert foreign entries concurrently.
 */
class PolicyCompilationCacheTest {
    @AfterEach
    fun tearDown() {
        io.mazewall.enforcer.api.PolicyCompilationCache
            .clear()
    }

    private fun policyWithPaths(vararg paths: String): PolicyDefinition<PolicyScope.ThreadLocalOnly> =
        PolicyBuilder<PolicyScope.ThreadLocalOnly>()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.CONNECT)
            .build()
            .let { base ->
                var b = PolicyBuilder<PolicyScope.ThreadLocalOnly>().base(base)
                for (p in paths) b = b.allowFsRead(p)
                b.build()
            }

    @Test
    fun `definitions differing only in fs paths share one cache entry`() {
        val d1 = policyWithPaths("/tmp/a")
        val d2 = policyWithPaths("/tmp/a", "/tmp/b")

        val c1 = io.mazewall.enforcer.api.PolicyCompilationCache
            .getOrCompile(d1, Arch.AMD64)
        // Same definition twice: must be the identical cached instance.
        assertSame(
            c1,
            io.mazewall.enforcer.api.PolicyCompilationCache
            .getOrCompile(d1, Arch.AMD64),
        )
        // Path-only variant: byte-identical program => same cached instance.
        assertSame(
            c1,
            io.mazewall.enforcer.api.PolicyCompilationCache
                .getOrCompile(d2, Arch.AMD64),
            "Definitions differing only in FS paths must share one cache entry",
        )
        assertEquals(c1.compiledFilters, c1.compiledFilters)
    }

    @Test
    fun `distinct syscall actions compile distinctly`() {
        val restrictive = PolicyDefinition<PolicyScope.ProcessWideSafe>(
            defaultAction = SeccompAction.ACT_ERRNO(),
            syscallActions = mapOf(Syscall.CONNECT to SeccompAction.ACT_ALLOW),
        )
        val permissive = PolicyDefinition<PolicyScope.ProcessWideSafe>()

        val r1 = io.mazewall.enforcer.api.PolicyCompilationCache
            .getOrCompile(restrictive, Arch.AMD64)
        assertSame(
            r1,
            io.mazewall.enforcer.api.PolicyCompilationCache
            .getOrCompile(restrictive, Arch.AMD64),
        )

        val p1 = io.mazewall.enforcer.api.PolicyCompilationCache
            .getOrCompile(permissive, Arch.AMD64)
        assertNotSame(r1, p1, "Different syscall actions must not share a cache entry")
        assertTrue(r1.compiledFilters != p1.compiledFilters)
    }

    @Test
    fun `cache is bounded`() {
        repeat(300) { i ->
            val def = PolicyDefinition<PolicyScope.ProcessWideSafe>(
                syscallActions = mapOf(Syscall.CONNECT to SeccompAction.ACT_TRACE(i)),
            )
            io.mazewall.enforcer.api.PolicyCompilationCache
                .getOrCompile(def, Arch.AMD64)
        }
        kotlin.test.assertTrue(
            io.mazewall.enforcer.api.PolicyCompilationCache
            .entryCount() <= 256,
        )
    }
}
