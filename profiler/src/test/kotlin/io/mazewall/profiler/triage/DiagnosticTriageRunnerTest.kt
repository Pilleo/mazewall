package io.mazewall.profiler.triage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DiagnosticTriageRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `triage records the failed task in the requested output file`() {
        val report = tempDir.resolve("reports/unit-check/report.json").toFile()

        DiagnosticTriageRunner.main(arrayOf("--failed-task", ":unitCheck", "--output", report.absolutePath))

        assertTrue(report.isFile)
        assertTrue(report.readText().contains("\"failed_task\": \":unitCheck\""))
    }

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
