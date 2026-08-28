package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.enforcer.state.ContainerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContainedExecutorsTest {

    @AfterEach
    fun tearDown() {
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun testIsPathSubsetLogic() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        val p1 = Policy.builder().allowFsRead("/tmp").build()
        val p2 = Policy.builder().allowFsRead("/").build()

        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withLandlockPolicy(p1.definition)
        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(p2)
        }
    }

    @Test
    fun testHandleUnsupportedPlatformBehaviors() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun hasKernelSeccompSupport(): Boolean = false
            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        System.setProperty("io.mazewall.fallback", "FAIL")
        assertFailsWith<UnsupportedOperationException> {
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())
        }

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())
    }

    @Test
    fun `unsupported-platform fallback receipt reports bypass instead of installation`() {
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"
                override fun hasKernelSeccompSupport(): Boolean = false
            },
        )
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")

        val receipt = ContainedExecutors.installOnCurrentThread(Policy.builder().build().definition)

        assertEquals(false, receipt.installed)
        assertEquals(false, receipt.landlockApplied)
    }

    @Test
    fun `failed installation fallback receipt reports bypass instead of installation`() {
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"
                override fun hasKernelSeccompSupport(): Boolean = true
                override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    LinuxNative.SyscallResult.Error(22, -1)
            },
        )
        val existingPolicy = Policy.builder().allowFsRead("/tmp").build()
        val incompatiblePolicy = Policy.builder().allowFsRead("/").build()
        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withLandlockPolicy(existingPolicy.definition)
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val receipt = ContainedExecutors.installOnCurrentThread(incompatiblePolicy.definition)

        assertEquals(false, receipt.installed)
        assertEquals(true, receipt.landlockApplied)
    }

    @Test
    fun testNonLinuxFallbackBehaviors() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "macOS"
        }
        Platform.setProvider(mockProvider)

        System.setProperty("io.mazewall.fallback", "FAIL")
        assertFailsWith<UnsupportedOperationException> {
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())
        }

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())
    }

    @Test
    fun `test thread-scoped containment disallowed on virtual threads`() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        val policy = Policy.builder().allowFsRead("/tmp").build()

        // 1. Verify Virtual Thread is rejected
        var virtualException: Throwable? = null
        val vThread = Thread.ofVirtual().start {
            try {
                ContainedExecutors.installOnCurrentThread(policy)
            } catch (t: Throwable) {
                virtualException = t
            }
        }
        vThread.join()
        assertTrue(
            virtualException is IllegalStateException,
            "Installing thread-scoped containment on a Virtual Thread must throw IllegalStateException, got: $virtualException"
        )
    }

    @Test
    fun `repeat install receipt reports active landlock when state already has landlock policy`() {
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"
                override fun hasKernelSeccompSupport(): Boolean = true
                override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    LinuxNative.SyscallResult.Error(22, -1)
            },
        )
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val policyWithLandlock = Policy.builder().allowFsRead("/tmp").build()
        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withLandlockPolicy(policyWithLandlock.definition)

        val receipt = ContainedExecutors.installOnCurrentThread(policyWithLandlock.definition)
        assertTrue(receipt.landlockApplied, "Repeat install with unchanged Landlock policy must report landlockApplied=true")

        // Policy with no Landlock on clean state must report landlockApplied=false
        ContainmentStateRegistry.threadState = io.mazewall.enforcer.state.ContainerState()
        val policyWithoutLandlock = Policy.builder().build()
        val receiptClean = ContainedExecutors.installOnCurrentThread(policyWithoutLandlock.definition)
        assertEquals(false, receiptClean.landlockApplied, "Policy without Landlock on clean state must report landlockApplied=false")
    }
}
