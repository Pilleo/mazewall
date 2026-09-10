package io.mazewall.demo.agent

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AgentScenarioFreshJvmTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `protected legitimate workload succeeds in a fresh JVM`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "legitimateBody")
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
    fun ssrfBody() {
        assertTrue(runAgentScenario("ssrf", useMazewall = true).passed)
    }

    @Suppress("unused")
    fun pathTraversalBody() {
        assertTrue(runAgentScenario("path_traversal", useMazewall = true).passed)
    }

    @Suppress("unused")
    fun maliciousRceBody() {
        assertTrue(runAgentScenario("rce_malicious", useMazewall = true).passed)
    }
}
