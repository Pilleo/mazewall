package io.mazewall.demo.agent

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
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
        listOf("ssrfBody", "pathTraversalBody", "maliciousRceBody").forEach { body ->
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
        }
    }

    @Suppress("unused")
    fun legitimateBody() {
        assertTrue(runAgentScenario("rce_legit", useMazewall = true).passed)
    }

    @Suppress("unused")
    fun legitimateFileBody() {
        assertTrue(runAgentScenario("file_legit", useMazewall = true).passed)
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

    private fun assertProtectedAttack(result: AgentScenarioResult) {
        when (result.outcome) {
            is AgentOutcome.Denied -> assertTrue(result.passed, result.render())
            is AgentOutcome.Inconclusive -> assertFalse(result.passed, result.render())
            is AgentOutcome.Completed -> fail("attack unexpectedly succeeded: ${result.render()}")
            is AgentOutcome.InfrastructureFailure -> fail(result.render())
        }
    }
}
