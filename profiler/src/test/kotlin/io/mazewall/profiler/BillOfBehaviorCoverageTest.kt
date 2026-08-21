package io.mazewall.profiler

import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Test
import kotlin.test.*

class BillOfBehaviorCoverageTest {

    @Test
    fun testToStackTracesJson() {
        val event = TraceEvent(1, "OPEN", longArrayOf(1), listOf("/tmp"))
        val stack = arrayOf(StackTraceElement("Class", "method", "File.kt", 1))
        val bob = BillOfBehavior(
            stackProfile = mapOf(event to listOf(stack))
        )
        val json = bob.toStackTracesJson()
        assertTrue(json.contains("OPEN"))
        assertTrue(json.contains("Class"))
    }

    @Test
    fun testFilterPaths() {
        val bob = BillOfBehavior(
            opens = setOf("/tmp/legit", "/etc/passwd"),
            fsWritePaths = setOf("/tmp/write", "/var/log/syslog"),
            execs = setOf("/bin/ls", "/usr/bin/evil")
        )

        val profile = BaselinePathProfile(
            exactPaths = setOf("/etc/passwd"),
            pathPrefixes = setOf("/var/log", "/usr/bin")
        )

        val filtered = bob.filterPaths(profile)
        assertEquals(setOf("/tmp/legit"), filtered.opens)
        assertEquals(setOf("/tmp/write"), filtered.fsWritePaths)
        assertEquals(setOf("/bin/ls"), filtered.execs)
    }

    @Test
    fun testFromJsonWithUnknownSyscall() {
        val json = """
            {
                "opens": [],
                "fsWritePaths": [],
                "syscalls": ["NON_EXISTENT_SYSCALL"],
                "execs": [],
                "stackProfile": []
            }
        """.trimIndent()
        val bob = BillOfBehavior.fromJson(json)
        assertTrue(bob.syscalls.isEmpty())
    }

    @Test
    fun `test unenforceable io_uring opcodes throw IncompleteProfileException on toPolicy unless allowIncomplete`() {
        val corr = ObservationCorrelation(1, Tid(1))
        val obs = listOf(
            ProfileObservation.IoUring(corr, ObservationSource.USER_NOTIF, "IORING_OP_CONNECT", listOf("/tmp/socket")),
        )
        val bob = BobCompiler.compileObservations(obs)
        assertTrue(bob.opens.isEmpty(), "IORING_OP_CONNECT path must not be placed into opens")
        assertTrue(bob.fsWritePaths.isEmpty(), "IORING_OP_CONNECT path must not be placed into fsWritePaths")

        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "test",
            processWide = false,
            observations = obs,
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
        )
        assertFalse(coverage.complete, "Unenforceable io_uring opcode must mark coverage incomplete")

        assertFailsWith<IncompleteProfileException> {
            bob.toPolicy(coverage = coverage, allowIncomplete = false)
        }

        val policy = bob.toPolicy(coverage = coverage, allowIncomplete = true)
        assertNotNull(policy)
    }
}
