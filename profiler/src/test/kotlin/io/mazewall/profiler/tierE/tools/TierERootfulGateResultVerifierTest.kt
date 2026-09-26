package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERootfulGateResultVerifierTest {
    @Test
    fun `native workflow skip cannot qualify a passing rootful gate`() {
        val output = Files.createTempDirectory("tier-e-rootful-verdict-")
        try {
            Files.writeString(output.resolve("native-ctest.log"), "native passed")
            Files.writeString(output.resolve("profiler-tests.log"), "gradle passed")
            Files.writeString(output.resolve("benchmark-gate-status.txt"), "PASS\n")
            val reports = Files.createDirectory(output.resolve("integrationTestFreshJvm"))
            Files.writeString(
                reports.resolve("TierEEndToEndIntegrationTest.xml"),
                """<testsuite name="TierEEndToEndIntegrationTest" tests="1" skipped="0" failures="0" errors="0"/>""",
            )
            val workflow = reports.resolve("TierEDaemonNativeWorkflowIntegrationTest.xml")
            Files.writeString(
                workflow,
                """<testsuite name="TierEDaemonNativeWorkflowIntegrationTest" tests="1" skipped="0" failures="0" errors="0"/>""",
            )
            assertTrue(TierERootfulGateResultVerifier.verify(output))
            Files.writeString(
                workflow,
                """<testsuite name="TierEDaemonNativeWorkflowIntegrationTest" tests="1" skipped="1" failures="0" errors="0"/>""",
            )
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateResultVerifier.verify(output) }
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
