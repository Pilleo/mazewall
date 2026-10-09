package io.mazewall.testing

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TestSuiteHealthListener : LauncherSessionListener {
    private companion object {
        val sessionSequence = AtomicInteger(0)
    }

    private val classStats = ConcurrentHashMap<String, Stats>()

    class Stats {
        val executed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val aborted = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val containerAborted = AtomicInteger(0)
        val containerFailed = AtomicInteger(0)
    }

    private fun getClassName(testIdentifier: TestIdentifier): String {
        return if (testIdentifier.isContainer) {
            val legacyName = testIdentifier.legacyReportingName
            if (legacyName != null && legacyName.isNotBlank() && legacyName != "null") legacyName else "Unknown"
        } else {
            val uniqueId = testIdentifier.uniqueId
            val classMatch = Regex("\\[class:([^\\]]+)\\]").find(uniqueId)
            classMatch?.groupValues?.get(1) ?: "Unknown"
        }
    }

    override fun launcherSessionOpened(session: LauncherSession) {
        session.launcher.registerTestExecutionListeners(object : TestExecutionListener {
            override fun executionSkipped(
                testIdentifier: TestIdentifier,
                reason: String?,
            ) {
                // Track skipped for both tests and containers (class-level skip)
                val className = getClassName(testIdentifier)
                // Filter out non-class containers (like engine or root nodes)
                if (className != "Unknown" && className != testIdentifier.uniqueId && !className.contains("engine:")) {
                    classStats.getOrPut(className) { Stats() }.skipped.incrementAndGet()
                } else if (testIdentifier.isTest) {
                     classStats.getOrPut(className) { Stats() }.skipped.incrementAndGet()
                }
            }

            override fun executionFinished(
                testIdentifier: TestIdentifier,
                testExecutionResult: TestExecutionResult,
            ) {
                if (testIdentifier.isTest) {
                    val className = getClassName(testIdentifier)
                    val stats = classStats.getOrPut(className) { Stats() }
                    stats.executed.incrementAndGet()
                    when (testExecutionResult.status) {
                        TestExecutionResult.Status.ABORTED -> stats.aborted.incrementAndGet() // Skipped by assumption
                        TestExecutionResult.Status.FAILED -> stats.failed.incrementAndGet()
                        TestExecutionResult.Status.SUCCESSFUL -> {}
                        null -> {}
                    }
                } else if (testIdentifier.isContainer) {
                    val className = classContainerName(testIdentifier) ?: return
                    val stats = classStats.getOrPut(className) { Stats() }
                    when (testExecutionResult.status) {
                        TestExecutionResult.Status.ABORTED -> stats.containerAborted.incrementAndGet()
                        TestExecutionResult.Status.FAILED -> stats.containerFailed.incrementAndGet()
                        TestExecutionResult.Status.SUCCESSFUL -> {}
                        null -> {}
                    }
                }
            }
        })
    }

    override fun launcherSessionClosed(session: LauncherSession) {
        val jsonLines = mutableListOf<String>()
        var totalExecuted = 0
        var totalSkipped = 0
        var totalAborted = 0
        var totalFailed = 0
        var totalContainerAborted = 0
        var totalContainerFailed = 0

        println("\n=== Test Tier Health Summary ===")
        for ((className, stats) in classStats.entries.sortedBy { it.key }) {
            val executed = stats.executed.get()
            val skipped = stats.skipped.get()
            val aborted = stats.aborted.get()
            val failed = stats.failed.get()
            val containerAborted = stats.containerAborted.get()
            val containerFailed = stats.containerFailed.get()

            totalExecuted += executed
            totalSkipped += skipped
            totalAborted += aborted
            totalFailed += failed
            totalContainerAborted += containerAborted
            totalContainerFailed += containerFailed

            println(
                String.format(
                    java.util.Locale.ROOT,
                    "Class: %-50s | Executed: %3d | Skipped: %3d | Aborted: %3d | Failed: %3d | Container aborted: %3d | Container failed: %3d",
                className,
                    executed,
                    skipped,
                    aborted,
                    failed,
                    containerAborted,
                    containerFailed,
                ),
            )

            jsonLines.add(
                """    "${escape(
                    className,
                )}": { "executed": $executed, "skipped": $skipped, "aborted": $aborted, "failed": $failed, "containerAborted": $containerAborted, "containerFailed": $containerFailed }""",
            )
        }
        println("--------------------------------")
        println(
            String.format(
                java.util.Locale.ROOT,
                "Total: %-50s | Executed: %3d | Skipped: %3d | Aborted: %3d | Failed: %3d | Container aborted: %3d | Container failed: %3d",
            "",
                totalExecuted,
                totalSkipped,
                totalAborted,
                totalFailed,
                totalContainerAborted,
                totalContainerFailed,
            ),
        )
        println("================================\n")

        val reportDir =
            File(
                System.getProperty(
                    "io.mazewall.testHealth.fragmentDir",
                    "build/reports/test-health/fragments",
                ),
            )
        val module = System.getProperty("io.mazewall.testHealth.module", "unknown")
        val task = System.getProperty("io.mazewall.testHealth.task", "unknown")
        val taskPath = System.getProperty("io.mazewall.testHealth.taskPath", "unknown")
        val invocationToken = System.getProperty("io.mazewall.testHealth.invocationToken", "missing")
        val fork = "${ProcessHandle.current().pid()}-${sessionSequence.incrementAndGet()}"
        val executionStatus =
            when {
                totalFailed > 0 || totalContainerFailed > 0 -> "failed"
                totalAborted > 0 || totalContainerAborted > 0 -> "aborted"
                else -> "completed"
            }
        reportDir.mkdirs()
        val reportFile = File(reportDir, "${safe(module)}-${safe(task)}-$fork.json")
        val payload =
            """{
              "invocationToken": "${escape(invocationToken)}",
              "module": "${escape(module)}",
              "task": "${escape(task)}",
              "taskPath": "${escape(taskPath)}",
              "fork": "${escape(fork)}",
              "executionStatus": "$executionStatus",
              "totals": { "executed": $totalExecuted, "skipped": $totalSkipped, "aborted": $totalAborted, "failed": $totalFailed, "containerAborted": $totalContainerAborted, "containerFailed": $totalContainerFailed },
              "classes": {
            ${jsonLines.joinToString(",\n")}
              }
            }
            """.trimIndent() + "\n"
        val temporary = Files.createTempFile(reportDir.toPath(), reportFile.name, ".tmp")
        Files.writeString(temporary, payload)
        try {
            Files.move(temporary, reportFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, reportFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val strictMode = System.getProperty("io.mazewall.strictTestTier") == "true"
        if (strictMode) {
            val totalSkipLike = totalSkipped + totalAborted + totalContainerAborted
            if (totalSkipLike > 0) {
                System.err.println("Strict Test Tier check failed: found $totalSkipLike skipped/aborted tests.")
                error("Strict Test Tier check failed: found $totalSkipLike skipped/aborted tests.")
            }
        }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun classContainerName(testIdentifier: TestIdentifier): String? {
        val segments = Regex("\\[(?:class|nested-class):([^\\]]+)]").findAll(testIdentifier.uniqueId).map { it.groupValues[1] }.toList()
        if (segments.isEmpty()) return null
        return buildString {
            append(segments.first())
            segments.drop(1).forEach { nested -> append('$').append(nested) }
        }
    }

    /** Encode a value as a JSON string fragment, including all control characters. */
    private fun escape(value: String): String =
        buildString(value.length + 16) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001F' -> {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                }
                else -> append(char)
            }
        }
    }
}
