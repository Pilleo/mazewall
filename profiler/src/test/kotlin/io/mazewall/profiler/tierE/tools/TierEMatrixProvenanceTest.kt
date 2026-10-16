package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEMatrixProvenanceTest {
    @Test
    fun `captured image native and jar identities detect post-arm binary drift`() {
        val root = Files.createTempDirectory("tier-e-provenance-")
        val native = root.resolve("libmazewall_stack_agent.so")
        val jar = root.resolve("profiler/build/install/tier-e-daemon/lib/tier-e-tools.jar")
        try {
            Files.createDirectories(jar.parent)
            Files.writeString(native, "native-v1")
            Files.writeString(jar, "jar-v1")
            val endpoint = ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock")
            val identity = TierEMatrixProvenance.capture(root, endpoint, "fixture", native) {
                ContainerCommandResult(0, "sha256:abcd\n")
            }
            assertTrue(identity.unchanged(root, endpoint, "fixture", native) { ContainerCommandResult(0, "sha256:abcd\n") })
            Files.writeString(jar, "jar-v2")
            assertFalse(identity.unchanged(root, endpoint, "fixture", native) { ContainerCommandResult(0, "sha256:abcd\n") })
            Files.writeString(jar, "jar-v1")
            assertFalse(identity.unchanged(root, endpoint, "fixture", native) { ContainerCommandResult(0, "sha256:other\n") })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
