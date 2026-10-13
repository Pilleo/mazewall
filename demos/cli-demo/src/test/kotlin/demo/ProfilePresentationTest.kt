package demo

import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.api.ProfilingResult
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("presentation")
@DisplayName("02 — Discover a behavioral contract, then validate it")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProfilePresentationTest {
    @Test
    @Order(1)
    @DisplayName("02.1 — Profile a real read and rerun it under the generated policy")
    fun observedBehavior() {
        check(Platform.isSupported()) { "Native profiling is required for this presentation case" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "profileBody")
    }

    @Test
    @Order(2)
    @DisplayName("02.2 — Missing coverage is a refusal to enforce, not permission")
    fun incompleteCoverage() {
        val incomplete = ProfilingResult("diagnostic only", BillOfBehavior(), emptyMap())
        assertFailsWith<IncompleteProfileException> { compileProfileForDemo(incomplete) }
        println("RESULT: incomplete observations cannot compile an enforcement policy")
        println("NEXT: policies apply on owned workers; understand their caller, admission and lifetime.")
    }

    @Suppress("unused")
    fun profileBody() {
        val report = runProfileAndEnforce()
        val positive = report.results.single { it.name == "contained legitimate workload" }
        assertTrue(positive.passed, report.summary())
        assertEquals("{\"version\":2}", assertIs<ScenarioOutcome.Completed>(positive.outcome).detail)
        val attack = report.results.single { it.name == "synchronous path attack" }
        when (val outcome = attack.outcome) {
            is ScenarioOutcome.Completed -> error("The unprofiled path was read: ${outcome.detail}")
            is ScenarioOutcome.Denied -> println("RESULT: ${attack.render()}")
            is ScenarioOutcome.InfrastructureFailure -> {
                val evidence = io.mazewall.enforcer.diagnostics.ContainmentViolationDetector.diagnose(outcome.cause)?.evidence
                assertEquals(io.mazewall.enforcer.api.ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, evidence)
                println("RESULT: unprofiled read fails; evidence is inferred permission failure, not authenticated denial")
            }
        }
        assertTrue(OwnedDemoRuntime.drainRetained().isEmpty())
        println("CONTROL: version2 proves the file was reread, not returned from a cache")
        println("LIMIT: this candidate covers the observed workload, not every future path\nCLEANUP: owned runtime drained")
    }
}
