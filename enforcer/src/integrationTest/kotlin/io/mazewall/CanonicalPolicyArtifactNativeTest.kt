package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CanonicalPolicyArtifactNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `serialized deny all remains active under the actual kernel`() {
        val file = Files.createTempFile("artifact-deny-all-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifySerializedPolicy", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifySerializedPolicy(file: String) {
        val original = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val document = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, original).toJson()
        val decoded = assertIs<CanonicalPolicyArtifact.Worker>(CanonicalPolicyArtifact.fromJson(document))
        val timeout = Duration.ofSeconds(10)
        ManagedRuntime.open(decoded.runtime, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(decoded.policy, DomainLimits(1, 1))
            assertFailsWith<ContainmentViolationException> {
                Mazewall.callContained(runtime, domain, Callable { Files.readString(Path.of(file)) })
            }
            assertEquals("caller-readable", Files.readString(Path.of(file)))
        }
    }
}
