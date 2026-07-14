package io.mazewall.profiler

import io.mazewall.core.Syscall
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
}
