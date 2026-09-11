package io.mazewall.demo.agent

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentScenarioFreshJvmTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `protected legitimate workload succeeds in a fresh JVM`() {
        listOf("legitimateBody", "legitimateFileBody").forEach { body ->
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `protected attacks are denied in fresh JVMs`() {
        listOf("pathTraversalBody", "maliciousRceBody", "ssrfBody").forEach { body ->
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
        }
    }

    @Test
    fun `inconclusive attack cannot pass fresh JVM verification`() {
        assertFailsWith<IllegalStateException> {
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "inconclusiveAttackBody")
        }
    }

    @Suppress("unused")
    fun legitimateBody() {
        val result = runAgentScenario("rce_legit", useMazewall = true)
        assertTrue(result.passed, result.render())
    }

    @Suppress("unused")
    fun legitimateFileBody() {
        val result = runAgentScenario("file_legit", useMazewall = true)
        assertTrue(result.passed, result.render())
    }

    @Suppress("unused")
    fun ssrfBody() {
        assertProtectedAttack(runAgentScenario("ssrf", useMazewall = true))
    }

    @Suppress("unused")
    fun pathTraversalBody() {
        assertProtectedAttack(runAgentScenario("path_traversal", useMazewall = true))
    }

    @Suppress("unused")
    fun maliciousRceBody() {
        assertProtectedAttack(runAgentScenario("rce_malicious", useMazewall = true))
    }

    @Suppress("unused")
    fun inconclusiveAttackBody() {
        assertProtectedAttack(
            AgentScenarioResult(
                "inconclusive",
                ExpectedAgentResult.DENIED,
                AgentOutcome.Inconclusive("INFERRED_PERMISSION_FAILURE"),
            ),
        )
    }

    private fun assertProtectedAttack(result: AgentScenarioResult) {
        when (result.outcome) {
            is AgentOutcome.Denied -> assertTrue(result.passed, result.render())
            is AgentOutcome.Inconclusive -> fail("attack denial is not authenticated: ${result.render()}")
            is AgentOutcome.Completed -> fail("attack unexpectedly succeeded: ${result.render()}")
            is AgentOutcome.InfrastructureFailure -> fail(result.render())
        }
    }
}
