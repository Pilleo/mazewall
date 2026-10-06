package io.mazewall.enforcer.api

import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolicyIntentNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `rebuilt active empty filesystem policy denies a fresh file open`() {
        val file = Files.createTempFile("policy-intent-native-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyDenyAllTemplate", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyDenyAllTemplate(file: String) {
        val path = Path.of(file)
        assertEquals("caller-readable", Files.readString(path))
        val left = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead("/policy-intent/left")
            .build()
        val right = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead("/policy-intent/right")
            .build()
        val intersected = PolicyDefinition.intersection(left.definition, right.definition)
        val rebuilt = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).base(intersected).build()
        assertTrue(rebuilt.definition.enforceLandlock)
        verifyDenyAllPolicy(file, rebuilt)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `explicit canonical deny all denies a fresh file open`() {
        val file = Files.createTempFile("policy-explicit-intent-native-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyExplicitDenyAll", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyExplicitDenyAll(file: String) {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        verifyDenyAllPolicy(file, policy)
    }

    private fun verifyDenyAllPolicy(
        file: String,
        policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>,
    ) {
        val path = Path.of(file)
        assertEquals("caller-readable", Files.readString(path))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val worker = ContainedExecutors.wrap(pool, policy)
            val failed = assertFailsWith<ExecutionException> {
                worker.submit(Callable { Files.readString(path) }).get(10, TimeUnit.SECONDS)
            }
            val cause = failed.cause
            assertTrue(
                cause is AccessDeniedException ||
                    (cause is ContainmentViolationException && cause.cause is AccessDeniedException),
                "The actual file open must be denied, not fail from unrelated installation or class loading: $cause",
            )
            assertEquals("caller-readable", Files.readString(path))
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
