package io.mazewall.demo.agent

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentDemoOutcomeTest {
    @Test
    fun `only observed policy denials count as authenticated containment`() {
        val denied = classifyToolOutcome(
            Result.failure(
                ExecutionException(
                    ContainmentViolationException(
                        "blocked",
                        evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
                    ),
                ),
            ),
        )
        val inferred = classifyToolOutcome(Result.failure(IOException("Permission denied")))
        val unknown = classifyToolOutcome(Result.failure(ContainmentViolationException("blocked")))

        assertTrue(denied is AgentOutcome.Denied)
        assertTrue(inferred is AgentOutcome.Inconclusive)
        assertTrue(unknown is AgentOutcome.Inconclusive)
    }

    @Test
    fun `ordinary operational failures remain infrastructure failures`() {
        assertTrue(classifyToolOutcome(Result.failure(IOException("network down"))) is AgentOutcome.InfrastructureFailure)
    }

    @Test
    fun `expected scenario result rejects attack success and legitimate denial`() {
        assertFalse(AgentScenarioResult("ssrf", ExpectedAgentResult.DENIED, AgentOutcome.Completed("metadata")).passed)
        assertFalse(AgentScenarioResult("rce_legit", ExpectedAgentResult.ALLOWED, AgentOutcome.Denied("EPERM")).passed)
        assertTrue(AgentScenarioResult("ssrf", ExpectedAgentResult.DENIED, AgentOutcome.Denied("EPERM")).passed)
        assertFalse(AgentScenarioResult("ssrf", ExpectedAgentResult.DENIED, AgentOutcome.Inconclusive("permission text")).passed)
    }

    @Test
    fun `render distinguishes security and operational scenario verdicts`() {
        assertTrue(
            AgentScenarioResult("ssrf", ExpectedAgentResult.DENIED, AgentOutcome.Denied("observed")).render()
                .contains("EXPECTED DENIAL"),
        )
        assertTrue(
            AgentScenarioResult("ssrf", ExpectedAgentResult.DENIED, AgentOutcome.Completed("metadata")).render()
                .contains("UNEXPECTED SUCCESS"),
        )
        assertTrue(
            AgentScenarioResult(
                "file_legit",
                ExpectedAgentResult.ALLOWED,
                AgentOutcome.Denied("unexpected policy denial"),
            ).render().contains("LEGITIMATE WORKLOAD FAILURE"),
        )
        assertTrue(
            AgentScenarioResult(
                "ssrf",
                ExpectedAgentResult.DENIED,
                AgentOutcome.Inconclusive("INFERRED_PERMISSION_FAILURE"),
            ).render().contains("INFRASTRUCTURE FAILURE"),
        )
    }

    @Test
    fun `private fixture creates no files outside its owned root`() {
        AgentDemoFixture.create().use { fixture ->
            assertEquals(fixture.root, fixture.workspace.parentFile)
            assertEquals(fixture.root, fixture.traversalTarget.parentFile)
            assertTrue(fixture.workspace.toPath().startsWith(fixture.root.toPath()))
        }
    }

    @Test
    fun `fixture cleanup deletes a symlink without deleting its external target`() {
        val externalRoot = Files.createTempDirectory("mazewall-agent-demo-external-")
        val externalFile = Files.writeString(externalRoot.resolve("keep.txt"), "keep")
        val fixture = AgentDemoFixture.create()
        Files.createSymbolicLink(fixture.workspace.toPath().resolve("external"), externalRoot)

        fixture.close()

        assertFalse(fixture.root.exists())
        assertTrue(Files.exists(externalFile))
        externalFile.toFile().delete()
        externalRoot.toFile().delete()
    }

    @Test
    fun `fatal errors escape scenario boundary after cleanup`() {
        var cleaned = false
        val fatal = AssertionError("fatal")

        val thrown = assertFailsWith<AssertionError> {
            runScenarioBoundary(
                action = { classifyToolOutcome(captureRecoverable { throw fatal }) },
                cleanup = { cleaned = true },
            )
        }

        assertEquals(fatal, thrown)
        assertTrue(cleaned)
    }

    @Test
    fun `cleanup failure cannot replace fatal scenario error`() {
        val fatal = AssertionError("fatal")
        val cleanup = IOException("cleanup failed")
        val thrown = assertFailsWith<AssertionError> {
            runScenarioBoundary(action = { throw fatal }, cleanup = { throw cleanup })
        }
        assertEquals(fatal, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
    }

    @Test
    fun `reflective and executor wrappers cannot turn a fatal error into an outcome`() {
        val fatal = OutOfMemoryError("fatal fixture")
        val thrown = assertFailsWith<OutOfMemoryError> {
            captureRecoverable<String> {
                throw java.lang.reflect.InvocationTargetException(ExecutionException(fatal))
            }
        }
        assertEquals(fatal, thrown)
    }

    @Test
    fun `fatal cleanup escapes an ordinary failed scenario`() {
        val ordinary = IOException("work failed")
        val fatal = LinkageError("cleanup failed")
        val thrown = assertFailsWith<LinkageError> {
            runScenarioBoundary(action = { throw ordinary }, cleanup = { throw fatal })
        }
        assertEquals(fatal, thrown)
        assertEquals(listOf(ordinary), thrown.suppressed.toList())
    }

    @Test
    fun `lookalike class and method names do not authorize process execution`() {
        AgentDemoFixture.create().use { fixture ->
            val tools = AgentTools(false, fixture.workspace)
            try {
                val authorize = AgentTools::class.java.getDeclaredMethod("authorizeExec", List::class.java)
                    .apply { isAccessible = true }
                listOf(
                    StackTraceElement("evil.AgentTools", "executeDataAnalysis", "evil.kt", 1),
                    StackTraceElement("io.mazewall.demo.agent.AgentTools", "executeDataAnalysisEvil", "evil.kt", 1),
                    StackTraceElement("evil.AgentTools", "executeAuthorizedAnalysis", "evil.kt", 1),
                    StackTraceElement("io.mazewall.demo.agent.AgentTools", "executeAuthorizedAnalysisEvil", "evil.kt", 1),
                ).forEach { frame ->
                    assertFalse(authorize.invoke(tools, listOf(frame)) as Boolean, frame.toString())
                }
                assertTrue(authorize.invoke(tools, listOf(
                    StackTraceElement("io.mazewall.demo.agent.AgentTools", "executeAuthorizedAnalysis", "Tools.kt", 1),
                )) as Boolean)
            } finally {
                tools.shutdown()
            }
        }
    }

    @Test
    fun `protected demo includes a benign file workload`() {
        val result = runAgentScenario("file_legit", useMazewall = false)

        assertTrue(result.passed)
        assertTrue(result.outcome is AgentOutcome.Completed)
    }
}
