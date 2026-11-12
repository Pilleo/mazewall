package io.mazewall.profiler

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.profiler.api.TierEAllTargetDaemonIntegrationTest
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@EnabledIfLinuxAndSupported
@Tag("tier-e-a4-boundary-diagnostic")
class TierEMissingOpenDiscriminatorIntegrationTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun captureOneOwnedGroupArm() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("gradlew")) }
        TierEAllTargetDaemonIntegrationTest().runInstalledDiagnostic(
            ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
            repository,
            Path.of(requireNotNull(System.getenv("TIER_E_SPLIT_NATIVE_BUILD_DIR"))).toAbsolutePath(),
            requireNotNull(System.getenv("TIER_E_DOCKER_IMAGE")),
        )
    }
}
