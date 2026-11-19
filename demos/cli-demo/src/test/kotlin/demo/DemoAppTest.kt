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
        assertTrue(report.passed, report.render())
        assertTrue(report.render().contains("INFERRED_PERMISSION_FAILURE"), report.render())
    }

    @Test
    fun `inferred permission text is never claimed as authenticated containment`() {
        val inferred = java.io.IOException("Permission denied")

        assertTrue(classifyContainmentFailure(inferred) is ScenarioOutcome.InfrastructureFailure)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `unsafe succeeds and controlled safe refusal passes with explicit inferred evidence`() {
        io.mazewall.enforcer.api.IsolatedProcessTester.runIsolatedMethod(javaClass.name, "unsafeAndSafeBody")
    }

    @Suppress("unused")
    fun unsafeAndSafeBody() {
        assertTrue(runUnsafe().passed)
        val safe = runSafe()
        assertTrue(safe.passed, safe.render())
        assertTrue(safe.render().contains("INFERRED_PERMISSION_FAILURE"), safe.render())
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `SafeRunner handles malformed payload`() {
        io.mazewall.enforcer.api.IsolatedProcessTester.runIsolatedMethod(javaClass.name, "malformedBody")
    }

    @Suppress("unused")
    fun malformedBody() {
        // This won't trigger the JNDI logic, so it's a simple success path
        SafeRunner.run("not-jndi")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `SafeRunner blocks execution in JNDI`() {
        io.mazewall.enforcer.api.IsolatedProcessTester.runIsolatedMethod(javaClass.name, "blockedBody")
    }

    @Suppress("unused")
    fun blockedBody() {
        val failure = assertFailsWith<ContainmentViolationException> {
            SafeRunner.run($$"${jndi:ldap://test?cmd=ls}")
        }
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(failure)?.evidence)
    }
}
