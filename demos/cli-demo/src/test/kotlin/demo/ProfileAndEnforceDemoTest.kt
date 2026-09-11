package demo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import io.mazewall.IsolatedProcessTester
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.mazewall.profiler.BillOfBehavior
import io.mazewall.profiler.ProfilingResult
import io.mazewall.profiler.IncompleteProfileException

class ProfileAndEnforceDemoTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `compiled file profile preserves the positive operation without stack supervision`() {
        if (!io.mazewall.Platform.isSupported()) return
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "compiledFileBody")
    }

    @Suppress("unused")
    fun compiledFileBody() {
        DemoFixture.create().use { fixture ->
            val file = fixture.root.resolve("config").toFile().apply { writeText("before") }
            val workload = ProfiledFileWorkload(file)
            val result = io.mazewall.profiler.Profiler.profile { workload.readConfiguration() }
            val policy = compileProfileForDemo(result)
            file.writeText("after")
            java.util.concurrent.Executors.newSingleThreadExecutor().use { executor ->
                val contained = io.mazewall.enforcer.api.ContainedExecutors.wrap(executor, policy)
                assertEquals("after", contained.submit(java.util.concurrent.Callable { workload.readConfiguration() }).get())
            }
        }
    }
    @Test
    fun `incomplete observation cannot compile an enforcement policy`() {
        val incomplete = ProfilingResult("diagnostic", BillOfBehavior(), emptyMap())
        assertFailsWith<IncompleteProfileException> { compileProfileForDemo(incomplete) }
    }

    @Test
    fun `positive workload reads the file again instead of reusing profiled content`() {
        DemoFixture.create().use { fixture ->
            val file = fixture.root.resolve("config").toFile()
            file.writeText("profiled")
            val workload = ProfiledFileWorkload(file)
            assertEquals("profiled", workload.readConfiguration())
            file.writeText("enforced")
            assertEquals("enforced", workload.readConfiguration())
        }
    }

    @Test
    fun `profile stack authority requires exact class and method`() {
        fun frame(owner: String, method: String) = StackTraceElement(owner, method, "test.kt", 1)
        assertTrue(ProfiledFileWorkload.authorizes(listOf(frame("demo.ProfiledFileWorkload", "readConfiguration"))))
        assertFalse(ProfiledFileWorkload.authorizes(listOf(frame("evil.ProfiledFileWorkload", "readConfiguration"))))
        assertFalse(ProfiledFileWorkload.authorizes(listOf(frame("demo.ProfiledFileWorkload", "readConfigurationEvil"))))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `profile demo does not claim success from inferred containment`() {
        if (!io.mazewall.Platform.isSupported()) return

        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "profileBody")
    }

    @Suppress("unused")
    fun profileBody() {
        val report = runProfileAndEnforce()
        assertTrue(report.results.single { it.name == "contained legitimate workload" }.passed, report.summary())
        val attacks = report.results.filter { it.expected == ExpectedResult.DENIED }
        assertEquals(3, attacks.size)
        attacks.forEach { result ->
            when (val outcome = result.outcome) {
                is ScenarioOutcome.Completed -> kotlin.test.fail(report.summary())
                is ScenarioOutcome.Denied -> assertTrue(result.passed)
                is ScenarioOutcome.InfrastructureFailure -> {
                    assertEquals(io.mazewall.enforcer.api.ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE,
                        io.mazewall.enforcer.diagnostics.ContainmentViolationDetector.diagnose(outcome.cause)?.evidence,
                        report.summary())
                    assertFalse(report.passed, report.summary())
                }
            }
        }
    }
}
