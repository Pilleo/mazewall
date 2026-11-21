package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortalRuntimeTest {
    interface MissingService

    interface OverloadedObjectService {
        fun toString(id: Int): String

        fun hashCode(seed: Int): Int

        fun equals(id: Int): Boolean
    }

    @Test
    fun `fatal stateful call preserves identity and terminal state loss`() {
        val manifest = sampleManifest("worker")
        val gate = PortalStatefulInvocationGate()
        val fatal = AssertionError("fatal invocation")
        val actual = assertThrows(AssertionError::class.java) { gate.invoke(manifest) { throw fatal } }
        check(actual === fatal)
        assertThrows(PortalStateLostException::class.java) { gate.invoke(manifest) { "must not run" } }
    }

    @Test
    fun `runtime configuration snapshots caller-owned selection collections`() {
        val manifest = sampleManifest("worker")
        val manifests = mutableListOf(manifest)
        val profile = PortalWorkerProfile()
        val profiles = mutableMapOf("restricted" to profile)
        val artifacts = mutableMapOf(
            "worker" to PortalWorkerArtifact(
                java.nio.file.Path
            .of("/tmp/worker.zip"),
                "a".repeat(64),
            ),
        )
        val config = PortalRuntimeConfig(manifests, artifacts, workerProfiles = profiles)
        manifests.clear()
        profiles.clear()
        artifacts.clear()
        assertEquals(listOf(manifest), config.manifests)
        assertEquals(profile, config.profile(manifest))
        assertEquals(setOf("worker"), config.workerArtifacts.keys)
    }

    @Test
    fun `overlapping stateful invocation rejects before body entry and cannot pass terminal loss`() {
        val gate = PortalStatefulInvocationGate()
        val manifest = sampleManifest("worker")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val extraCalls = AtomicInteger()
        Executors.newSingleThreadExecutor().use { worker ->
            try {
                val active = worker.submit {
                    gate.invoke(manifest) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw PortalCallException("ambiguous worker loss", PortalInvocationEvidence.UNKNOWN_COMPLETION)
                    }
                }
                check(entered.await(5, TimeUnit.SECONDS))
                val refused = assertThrows(PortalCallException::class.java) { gate.invoke(manifest) { extraCalls.incrementAndGet() } }
                assertEquals(PortalInvocationEvidence.NOT_STARTED, refused.evidence)
                assertEquals(0, extraCalls.get())
                release.countDown()
                assertThrows(java.util.concurrent.ExecutionException::class.java) { active.get(5, TimeUnit.SECONDS) }
                assertThrows(PortalStateLostException::class.java) { gate.invoke(manifest) { extraCalls.incrementAndGet() } }
                assertEquals(0, extraCalls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `closed runtime rejects all lookup without starting a worker`() {
        val runtime = PortalRuntime.open(PortalRuntimeConfig(emptyList()))
        runtime.close()
        runtime.close()

        assertThrows(PortalRuntimeClosedException::class.java) {
            runtime.get(MissingService::class.java)
        }
    }

    @Test
    fun `invocation failures expose completion certainty`() {
        val failure = PortalCallException("queue timeout", PortalInvocationEvidence.NOT_STARTED)

        assertEquals(PortalInvocationEvidence.NOT_STARTED, failure.evidence)
    }

    @Test
    fun `unknown completion makes a stateful group unavailable`() {
        val manifest = sampleManifest("worker")
        val gate = PortalStatefulInvocationGate()

        assertThrows(PortalCallException::class.java) {
            gate.invoke(manifest) {
                throw PortalCallException("worker disappeared", PortalInvocationEvidence.UNKNOWN_COMPLETION)
            }
        }

        assertThrows(PortalStateLostException::class.java) { gate.invoke(manifest) { "must not run" } }
        assertEquals(PortalServiceHealthState.STATE_LOST, gate.health(manifest).state)
    }

    @Test
    fun `artifact resolver rejects a missing declared artifact`() {
        val manifest =
            PortalServiceManifest(
                serviceId = PortalServiceId("missing"),
                contractName = MissingService::class.java.name,
                implementationName = "fixture.MissingServiceImpl",
                dispatcherName = "fixture.MissingServicePortalDispatcher",
                schemaId = "schema",
                isolationGroup = "missing",
                instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
                policyProfile = "restricted",
                workerArtifact = "/definitely/not/a/portal-worker.jar",
            )

        assertThrows(PortalWorkerArtifactUnavailableException::class.java) {
            PortalRuntimeConfig(listOf(manifest)).workerClasspath(manifest)
        }
    }

    @Test
    fun `artifact resolver launches only jars materialized from the verified worker bundle`() {
        val bundle = Files.createTempFile("portal-worker", ".zip")
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            zip.putNextEntry(ZipEntry("lib/worker.jar"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val manifest = sampleManifest("worker")
        val config =
            PortalRuntimeConfig(
                manifests = listOf(manifest),
                workerArtifacts = mapOf("worker" to PortalWorkerArtifact(bundle, sha256(bundle))),
                workerBundleCache = Files.createTempDirectory("portal-cache"),
            )

        val classpath = config.workerClasspath(manifest)

        check(
            java.nio.file.Path
            .of(classpath)
            .fileName
            .toString() == "worker.jar",
        ) { classpath }
        check(
            Files.isRegularFile(
                java.nio.file.Path
            .of(classpath),
            ),
        )
    }

    @Test
    fun `artifact resolver keys cached bundles by both artifact digest and schema`() {
        val bundle = Files.createTempFile("portal-worker", ".zip")
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            zip.putNextEntry(ZipEntry("lib/worker.jar"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val artifact = PortalWorkerArtifact(bundle, sha256(bundle))
        val cache = Files.createTempDirectory("portal-cache")
        val first = sampleManifest("worker")
        val second = first.copy(schemaId = "changed-schema")
        val config = PortalRuntimeConfig(listOf(first, second), mapOf("worker" to artifact), cache)

        val firstJar = java.nio.file.Path
            .of(config.workerClasspath(first))
        val secondJar = java.nio.file.Path
            .of(config.workerClasspath(second))

        assertNotEquals(firstJar.parent.parent, secondJar.parent.parent)
    }

    @Test
    fun `proxy dispatches Object-name overloads while retaining local Object identity`() {
        val manifest =
            sampleManifest("worker").copy(
                serviceId = PortalServiceId("overload"),
                contractName = OverloadedObjectService::class.java.name,
            )
        val runtime =
            PortalRuntime.open(
                PortalRuntimeConfig(
                    manifests = listOf(manifest),
                    workerProfiles = mapOf("restricted" to PortalWorkerProfile()),
                ),
            )
        try {
            val calls = AtomicInteger(0)
            val delegate =
                object : OverloadedObjectService {
                    override fun toString(id: Int): String {
                        calls.incrementAndGet()
                        return "remote-$id"
                    }

                    override fun hashCode(seed: Int): Int {
                        calls.incrementAndGet()
                        return seed + 1
                    }

                    override fun equals(id: Int): Boolean {
                        calls.incrementAndGet()
                        return id == 7
                    }
                }

            val proxy = runtime.createServiceProxy(OverloadedObjectService::class.java, manifest, delegate)

            assertEquals("PortalProxy(overload)", proxy.toString())
            assertEquals(System.identityHashCode(proxy), proxy.hashCode())
            assertEquals(true, proxy.equals(proxy))
            assertEquals(false, proxy.equals("other"))
            assertEquals(0, calls.get())

            assertEquals("remote-7", proxy.toString(7))
            assertEquals(8, proxy.hashCode(7))
            assertEquals(true, proxy.equals(7))
            assertEquals(false, proxy.equals(8))
            assertEquals(4, calls.get())

            runtime.close()
            assertThrows(PortalRuntimeClosedException::class.java) { proxy.toString(7) }
            assertThrows(PortalRuntimeClosedException::class.java) { proxy.hashCode(7) }
            assertThrows(PortalRuntimeClosedException::class.java) { proxy.equals(7) }
            assertEquals(4, calls.get())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `stateful proxy distinguishes business and dto exceptions from malformed wire replies`() {
        val manifest =
            sampleManifest("worker").copy(
                serviceId = PortalServiceId("stateful-echo"),
                contractName = StatefulEchoService::class.java.name,
            )
        val runtime =
            PortalRuntime.open(
                PortalRuntimeConfig(
                    manifests = listOf(manifest),
                    workerProfiles = mapOf("restricted" to PortalWorkerProfile()),
                ),
            )
        try {
            val dtoError = IllegalArgumentException("rejected by dto")
            val calls = AtomicInteger(0)
            val delegate =
                object : StatefulEchoService {
                    override fun call(mode: String): String {
                        calls.incrementAndGet()
                        return when (mode) {
                            "business" -> throw PortalRemoteCallException("fixture.Invalid", "bad input")
                            "dto" -> throw dtoError
                            "malformed" -> throw PortalMalformedPayloadException("trailing bytes", "test.call.return")
                            else -> "ok-$mode"
                        }
                    }
                }
            val proxy = runtime.createServiceProxy(StatefulEchoService::class.java, manifest, delegate)

            assertEquals("ok-first", proxy.call("first"))
            val business = assertThrows(PortalRemoteCallException::class.java) { proxy.call("business") }
            assertEquals(PortalInvocationEvidence.COMPLETED, business.evidence)
            val actualDtoError = assertThrows(IllegalArgumentException::class.java) { proxy.call("dto") }
            check(actualDtoError === dtoError)
            assertEquals("ok-after-errors", proxy.call("after-errors"))
            assertEquals(PortalServiceHealthState.AVAILABLE, runtime.health().single().state)

            assertThrows(PortalMalformedPayloadException::class.java) { proxy.call("malformed") }
            assertEquals(PortalServiceHealthState.STATE_LOST, runtime.health().single().state)
            assertEquals(5, calls.get())
            assertThrows(PortalStateLostException::class.java) { proxy.call("after-lost") }
            assertEquals(5, calls.get())
        } finally {
            runtime.close()
        }
    }

    private interface StatefulEchoService {
        fun call(mode: String): String
    }

    private fun sampleManifest(artifact: String): PortalServiceManifest =
        PortalServiceManifest(
            serviceId = PortalServiceId("missing"),
            contractName = MissingService::class.java.name,
            implementationName = "fixture.MissingServiceImpl",
            dispatcherName = "fixture.MissingServicePortalDispatcher",
            schemaId = "schema",
            isolationGroup = "missing",
            instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
            policyProfile = "restricted",
            workerArtifact = artifact,
        )

    private fun sha256(file: java.nio.file.Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
