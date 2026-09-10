package io.mazewall.demo.agent

import io.mazewall.enforcer.api.ContainmentViolationException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDemoOutcomeTest {
    @Test
    fun `containment detector classifies wrapped denials separately from infrastructure failures`() {
        val denied = classifyToolOutcome(
            Result.failure(ExecutionException(ContainmentViolationException("blocked"))),
        )
        val failed = classifyToolOutcome(Result.failure(IOException("network down")))

        assertTrue(denied is AgentOutcome.Denied)
        assertTrue(failed is AgentOutcome.InfrastructureFailure)
    }

    @Test
    fun `expected scenario result rejects attack success and legitimate denial`() {
        assertFalse(AgentScenarioResult("ssrf", ExpectedAgentResult.DENIED, AgentOutcome.Completed("metadata")).passed)
        assertFalse(AgentScenarioResult("rce_legit", ExpectedAgentResult.ALLOWED, AgentOutcome.Denied("EPERM")).passed)
        assertTrue(AgentScenarioResult("ssrf", ExpectedAgentResult.DENIED, AgentOutcome.Denied("EPERM")).passed)
    }

    @Test
    fun `private fixture creates no files outside its owned root`() {
        AgentDemoFixture.create().use { fixture ->
            assertEquals(fixture.root, fixture.workspace.parentFile)
            assertEquals(fixture.root, fixture.traversalTarget.parentFile)
            assertTrue(fixture.workspace.toPath().startsWith(fixture.root.toPath()))
        }
    }
}
