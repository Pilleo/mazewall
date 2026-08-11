package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.EnabledIfCetSupported
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.install
import io.mazewall.enforcer.ContainedExecutors
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertTrue

class IntelCetIntegrationTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `queryIntelCetStatus executes without crashing on real kernel`() {
        val status = Platform.queryIntelCetStatus()
        // Status should be >= 0 (either 0 if unsupported, or a positive bitmask if supported)
        assertTrue(status >= 0L)
    }

    @Test
    @EnabledIfCetSupported
    fun `queryIntelCetStatus returns active status when CET is supported and locked`() {
        val policy = Policy.builder().lockIntelCet().build()
        policy.install().use {
            val status = Platform.queryIntelCetStatus()
            assertTrue((status and io.mazewall.ffi.NativeConstants.ARCH_SHSTK_SHSTK) != 0L, "Expected ARCH_SHSTK_SHSTK to be active")
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `armIntelCet is bypassable under WARN_AND_BYPASS when unsupported`() {
        val executor = Executors.newSingleThreadExecutor()
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        try {
            val policy = Policy.builder().lockIntelCet().build()
            val safeExecutor = ContainedExecutors.wrap(executor, policy)

            // Even if CET is not supported on the test runner, it should bypass and complete successfully under WARN_AND_BYPASS
            safeExecutor.submit {
                // Task should execute successfully
            }.get()
        } finally {
            System.clearProperty("io.mazewall.fallback")
            executor.shutdown()
        }
    }
}
