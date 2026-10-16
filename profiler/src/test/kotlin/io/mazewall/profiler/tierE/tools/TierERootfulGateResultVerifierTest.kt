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
            Files.writeString(output.resolve("benchmark-gate-contract.txt"), "2\n")
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
            val pinned = "sha256:${"a".repeat(64)}"
            val admission = output.resolve("image-admission.json")
            Files.writeString(output.resolve("native-build-identity.txt"), "source_digest=${"c".repeat(64)}\nimage_id=$pinned\npolicy_eligibility=false\n")
            Files.writeString(
                admission,
                """{"schema_version":1,"mode":"PINNED_IMAGE_ID","image_id":"$pinned","expected_image_id":"$pinned","image_reference":"$pinned","containerfile_sha256":"${"b".repeat(
                    64,
                )}","source_digest":"${"c".repeat(64)}","policy_eligibility":false}""",
            )
            assertTrue(TierERootfulGateResultVerifier.verifyPinned(output, pinned))
            Files.writeString(admission, Files.readString(admission).replace("\"image_reference\":\"$pinned\"", "\"image_reference\":\"sha256:${"d".repeat(64)}\""))
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateResultVerifier.verifyPinned(output, pinned) }
            Files.delete(admission)
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateResultVerifier.verifyPinned(output, pinned) }
            Files.writeString(output.resolve("benchmark-gate-contract.txt"), "1\n")
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateResultVerifier.verify(output) }
            Files.writeString(output.resolve("benchmark-gate-contract.txt"), "2\n")
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
