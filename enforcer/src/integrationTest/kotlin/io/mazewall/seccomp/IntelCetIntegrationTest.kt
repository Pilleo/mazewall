package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntelCetIntegrationTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `read-only enabled status agrees with current thread proc evidence`() {
        val status = Platform.queryIntelCetStatus()
        val proc = Files.readString(Path.of("/proc/thread-self/status"))
        assertEquals(procFeatures(proc, "x86_Thread_features").contains("shstk"), (status and 1L) != 0L)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `pre-enabled compatible runtime confirms current thread locking`() {
        assumeTrue((Platform.queryIntelCetStatus() and 1L) != 0L, "Native CET lock acceptance requires a runtime/loader with SHSTK already enabled")
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyPreenabledThreadLock")
    }

    fun verifyPreenabledThreadLock() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        assertTrue((Platform.queryIntelCetStatus() and 1L) != 0L, "Child runtime must also start with SHSTK enabled")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()
        ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
            receipt.requireInstalled()
            assertEquals(CetOutcome.VERIFIED_CURRENT_THREAD, receipt.cetOutcome)
            assertEquals(true, receipt.progress?.cetLocked)
            val proc = Files.readString(Path.of("/proc/thread-self/status"))
            assertTrue(procFeatures(proc, "x86_Thread_features").contains("shstk"))
            assertTrue(procFeatures(proc, "x86_Thread_features_locked").contains("shstk"))
        }
        ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
            receipt.requireInstalled()
            assertEquals(CetOutcome.VERIFIED_CURRENT_THREAD, receipt.cetOutcome)
            assertEquals(false, receipt.progress?.cetLocked)
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `inactive runtime rejects required CET even with manual bypass`() {
        assumeTrue((Platform.queryIntelCetStatus() and 1L) == 0L, "This rejection fixture requires SHSTK inactive at runtime startup")
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyInactiveThreadRejection")
    }

    fun verifyInactiveThreadRejection() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        val calls = AtomicInteger()
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()
        Executors.newSingleThreadExecutor().use { worker ->
            assertEquals(0L, worker.submit(Callable { Platform.queryIntelCetStatus() and 1L }).get(10, TimeUnit.SECONDS))
            assertFailsWith<ExecutionException> {
                ContainedExecutors.wrap(worker, policy).submit(Callable { calls.incrementAndGet() }).get(10, TimeUnit.SECONDS)
            }
            assertEquals(0L, worker.submit(Callable { Platform.queryIntelCetStatus() and 1L }).get(10, TimeUnit.SECONDS), "Library must not late-enable shadow stack")
        }
        assertEquals(0, calls.get())
    }

    private fun procFeatures(
        status: String,
        field: String,
    ): Set<String> =
        status
        .lineSequence()
        .firstOrNull { it.startsWith("$field:") }
        ?.substringAfter(':')
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.filter(String::isNotEmpty)
        ?.toSet() ?: emptySet()
}
