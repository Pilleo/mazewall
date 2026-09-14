package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortalWorkerProfileTest {
    @Test
    fun `limited profile requires fixed JVM headroom`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            PortalWorkerProfile(
                maxHeapBytes = 64L * MIB,
                memoryMaxBytes = 64L * MIB + 32L * MIB - 1,
            )
        }

        assertTrue(failure.message!!.contains("headroom"))
    }

    @Test
    fun `limited profile exposes JVM heap and cgroup requirements`() {
        val profile = PortalWorkerProfile(
            maxHeapBytes = 64L * MIB,
            memoryMaxBytes = 128L * MIB,
            cpuLimit = PortalCpuLimit(Duration.ofMillis(50), Duration.ofMillis(100)),
        )

        assertTrue(profile.requiresCgroup)
        assertTrue(checkNotNull(profile.jvmMaxHeapArgument()).contains("67108864"))
    }

    @Test
    fun `runtime rejects an unresolved manifest profile before worker startup`() {
        val config = PortalRuntimeConfig(
            manifests = listOf(sampleManifest()),
            workerProfiles = emptyMap(),
        )

        val failure = assertFailsWith<PortalWorkerProfileUnavailableException> { PortalRuntime.open(config) }

        assertTrue(failure.message!!.contains("restricted"))
    }

    @Test
    fun `limited profile without delegated cgroup root is rejected before worker startup`() {
        val profile = PortalWorkerProfile(maxHeapBytes = 64L * MIB, memoryMaxBytes = 128L * MIB)
        val config = PortalRuntimeConfig(listOf(sampleManifest()), workerProfiles = mapOf("restricted" to profile))

        val failure = assertFailsWith<PortalCgroupUnavailableException> { PortalRuntime.open(config) }

        assertTrue(failure.message!!.contains("cgroup"))
    }

    @Test
    fun `unbounded profile opens without a cgroup root`() {
        val runtime = PortalRuntime.open(
            PortalRuntimeConfig(
                manifests = listOf(sampleManifest()),
                workerProfiles = mapOf("restricted" to PortalWorkerProfile()),
            ),
        )

        runtime.close()
    }

    @Test
    fun `health exposes the resolved profile without starting a worker`() {
        val profile = PortalWorkerProfile()
        val runtime = PortalRuntime.open(
            PortalRuntimeConfig(
                manifests = listOf(sampleManifest()),
                workerProfiles = mapOf("restricted" to profile),
            ),
        )

        val health = runtime.health().single()

        assertEquals("restricted", health.policyProfile)
        assertEquals(profile, health.workerProfile)
        assertEquals(emptyList<Long>(), health.workerPids)
        assertEquals(PortalWorkerLifecycleState.NOT_STARTED, health.lastWorkerLifecycle.state)
        runtime.close()
    }

    private fun sampleManifest(): PortalServiceManifest =
        PortalServiceManifest(
            serviceId = PortalServiceId("profile-test"),
            contractName = Runnable::class.java.name,
            implementationName = "fixture.ProfileTestImpl",
            dispatcherName = "fixture.ProfileTestDispatcher",
            schemaId = "profile-schema",
            isolationGroup = "profile-test",
            instanceMode = PortalInstanceMode.STATELESS,
            policyProfile = "restricted",
            workerArtifact = "not-resolved-before-validation",
        )

    private companion object {
        const val MIB: Long = 1024L * 1024L
    }
}
