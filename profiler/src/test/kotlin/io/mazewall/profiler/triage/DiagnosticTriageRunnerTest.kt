package io.mazewall.profiler.triage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class DiagnosticTriageRunnerTest {

    @Test
    fun `test triage runner creates report file`() {
        // Run main method which generates report in build/triage_report.json
        DiagnosticTriageRunner.main(emptyArray())

        val reportFile = File("build/triage_report.json")
        assertTrue(reportFile.exists())

        val content = reportFile.readText()
        assertTrue(content.contains("timestamp"))
        assertTrue(content.contains("diagnostics"))
        assertTrue(content.contains("dmesg_seccomp_logs"))
        assertTrue(content.contains("jvm_thread_dump"))
        assertTrue(content.contains("system_syscall_definitions"))
        assertTrue(content.contains("hs_err_logs"))
        assertTrue(content.contains("kernel_security_config"))
    }
}
