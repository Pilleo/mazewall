package io.mazewall.testing

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TestSuiteHealthListener : LauncherSessionListener {
    private val classStats = ConcurrentHashMap<String, Stats>()

    class Stats {
        val executed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val aborted = AtomicInteger(0)
        val failed = AtomicInteger(0)
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
            override fun executionSkipped(testIdentifier: TestIdentifier, reason: String?) {
                // Track skipped for both tests and containers (class-level skip)
                val className = getClassName(testIdentifier)
                // Filter out non-class containers (like engine or root nodes)
                if (className != "Unknown" && className != testIdentifier.uniqueId && !className.contains("engine:")) {
                    classStats.getOrPut(className) { Stats() }.skipped.incrementAndGet()
                } else if (testIdentifier.isTest) {
                     classStats.getOrPut(className) { Stats() }.skipped.incrementAndGet()
                }
            }

            override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
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

        println("\n=== Test Tier Health Summary ===")
        for ((className, stats) in classStats.entries.sortedBy { it.key }) {
            val executed = stats.executed.get()
            val skipped = stats.skipped.get()
            val aborted = stats.aborted.get()
            val failed = stats.failed.get()

            totalExecuted += executed
            totalSkipped += skipped
            totalAborted += aborted
            totalFailed += failed

            println(String.format("Class: %-50s | Executed: %3d | Skipped: %3d | Aborted(Assumptions): %3d | Failed: %3d",
                className, executed, skipped, aborted, failed))

            jsonLines.add("""    "$className": { "executed": $executed, "skipped": $skipped, "aborted": $aborted, "failed": $failed }""")
        }
        println("--------------------------------")
        println(String.format("Total: %-50s | Executed: %3d | Skipped: %3d | Aborted(Assumptions): %3d | Failed: %3d",
            "", totalExecuted, totalSkipped, totalAborted, totalFailed))
        println("================================\n")

        val reportDir = File("build/reports")
        @Suppress("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
        reportDir.mkdirs()
        val reportFile = File(reportDir, "test-tier-health.json")
        reportFile.writeText("{\n" + jsonLines.joinToString(",\n") + "\n}\n")

        val strictMode = System.getProperty("io.mazewall.strictTestTier") == "true"
        if (strictMode) {
            val totalSkipLike = totalSkipped + totalAborted
            if (totalSkipLike > 0) {
                System.err.println("Strict Test Tier check failed: found $totalSkipLike skipped/aborted tests.")
                throw RuntimeException("Strict Test Tier check failed: found $totalSkipLike skipped/aborted tests.")
            }
        }
    }
}
