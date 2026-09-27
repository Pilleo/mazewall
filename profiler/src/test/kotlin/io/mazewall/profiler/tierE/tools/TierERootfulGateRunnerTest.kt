package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERootfulGateRunnerTest {
    @Test
    fun `unchanged profiler verification does not force a full rerun`() {
        val command = TierERootfulGateRunner.profilerVerificationCommand("owned-runner")
        assertTrue(command.contains(":profiler:integrationTestFreshJvm"))
        assertFalse(command.contains("--rerun-tasks"))
        assertTrue(command.contains("TIER_E_AGENT_LIBRARY=/workspace/profiler-native/build/libmazewall_stack_agent.so"))
    }

    @Test
    fun `replayed comparable phase analysis records both modes even if the first breaches its bound`() {
        val output = Files.createTempDirectory("tier-e-phase-replay-")
        try {
            Files.writeString(output.resolve("full-1.log"), "broken first sample")
            val decision = TierERootfulPhaseEvidence.verify(output, output.resolve("baseline.env"))
            assertTrue(decision.isFailure)
            assertTrue(Files.exists(output.resolve("phase-full-verdict.txt")))
            assertTrue(Files.exists(output.resolve("phase-unique-verdict.txt")))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native build cache identity changes with native inputs and toolchain`() {
        val one = TierERootfulPhaseProbe.nativeBuildKey("source-1", "image-1", "compiler-1", "jdk-25")
        assertTrue(one != TierERootfulPhaseProbe.nativeBuildKey("source-2", "image-1", "compiler-1", "jdk-25"))
        assertTrue(one != TierERootfulPhaseProbe.nativeBuildKey("source-1", "image-2", "compiler-1", "jdk-25"))
        assertTrue(one != TierERootfulPhaseProbe.nativeBuildKey("source-1", "image-1", "compiler-2", "jdk-25"))
        assertTrue(one != TierERootfulPhaseProbe.nativeBuildKey("source-1", "image-1", "compiler-1", "jdk-26"))
    }

    @Test
    fun `aggregate gate caches native build only for identical source and toolchain`() {
        val first = TierERootfulGateRunner.nativeBuildCache("source-a", "image-a", "compiler-a", "jdk-a")
        assertTrue(first.startsWith("mazewall-tier-e-rootful-native-"))
        assertTrue(first != TierERootfulGateRunner.nativeBuildCache("source-b", "image-a", "compiler-a", "jdk-a"))
        assertTrue(first != TierERootfulGateRunner.nativeBuildCache("source-a", "image-b", "compiler-a", "jdk-a"))
        assertTrue(first != TierERootfulGateRunner.nativeBuildCache("source-a", "image-a", "compiler-b", "jdk-a"))
        assertTrue(first != TierERootfulGateRunner.nativeBuildCache("source-a", "image-a", "compiler-a", "jdk-b"))
    }

    @Test
    fun `remote engine cannot receive local source or create artifact volume`() {
        val root = Files.createTempDirectory("tier-e-gate-root-")
        val output = Files.createTempDirectory("tier-e-gate-output-")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                TierERootfulGateRunner.run(root, output, ContainerEndpoint(ContainerEngine.DOCKER, "ssh://remote"), "fixture")
            }
            assertTrue(Files.list(output).use { it.findAny().isEmpty })
        } finally {
            root.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }
}
