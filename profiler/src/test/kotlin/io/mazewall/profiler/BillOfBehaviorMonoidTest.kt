package io.mazewall.profiler

import io.mazewall.core.Syscall
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Monoid laws for BillOfBehavior composition (issue-018).
 * Fixed-sample associativity checks (property framework intentionally not introduced here).
 */
class BillOfBehaviorMonoidTest {

    private fun sampleA() = BillOfBehavior(
        opens = setOf("/tmp/a"),
        syscalls = setOf(Syscall.CONNECT),
    )

    private fun sampleB() = BillOfBehavior(
        fsWritePaths = setOf("/var/b"),
        connects = setOf(NetworkEndpoint("127.0.0.1", 8080)),
    )

    private fun sampleC() = BillOfBehavior(
        execs = setOf("/usr/bin/env"),
        ioUringOps = setOf("IORING_OP_OPENAT"),
        stackProfile = emptyMap(),
    )

    @Test
    fun `EMPTY is left and right identity`() {
        val a = sampleA()
        assertEquals(a, BillOfBehavior.EMPTY + a)
        assertEquals(a, a + BillOfBehavior.EMPTY)
        assertEquals(BillOfBehavior.empty(), BillOfBehavior.EMPTY)
    }

    @Test
    fun `plus is associative on fixed samples`() {
        val a = sampleA()
        val b = sampleB()
        val c = sampleC()

        assertEquals((a + b) + c, a + (b + c))
    }

    @Test
    fun `fold with EMPTY equals reduce chain`() {
        val list = listOf(sampleA(), sampleB(), sampleC(), BillOfBehavior.empty())
        val reduced = list.reduce(BillOfBehavior::plus)
        val folded = list.fold(BillOfBehavior.empty(), BillOfBehavior::plus)

        assertEquals(reduced, folded)
        assertTrue(reduced.syscalls.containsAll(sampleA().syscalls))
    }
}
