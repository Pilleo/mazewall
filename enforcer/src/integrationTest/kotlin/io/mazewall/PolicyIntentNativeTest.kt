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
        val left = Policy
            .threadLocalBuilder()
            .forRuntime(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead("/policy-intent/left")
            .build()
        val right = Policy
            .threadLocalBuilder()
            .forRuntime(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead("/policy-intent/right")
            .build()
        val intersected = PolicyDefinition.intersection(left.definition, right.definition)
        val rebuilt = PolicyBuilder<PolicyScope.ThreadLocalOnly>().base(intersected).build()
        assertTrue(rebuilt.enforceLandlock)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val worker = ContainedExecutors.wrap(pool, Policy(rebuilt))
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
