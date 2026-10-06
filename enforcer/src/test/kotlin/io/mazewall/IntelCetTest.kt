package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IntelCetTest {
    private val mockEngine = MockNativeEngine()

    @BeforeEach
    fun setUp() {
        LinuxNative.setEngine(mockEngine)
        Platform.resetToDefault()
        Platform.isCpuCetSupportedOverride = true
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        Platform.isCpuCetSupportedOverride = null
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    @Test
    fun `isCpuCetSupported works correctly`() {
        // Clear override to test real or cached behavior
        Platform.isCpuCetSupportedOverride = null
        val supported = Platform.isCpuCetSupported()
        // It shouldn't throw, and should return boolean without error
        assertNotNull(supported)
    }

    @Test
    fun `queryIntelCetStatus returns enabled status correctly`() {
        var statusWritten = false
        val interceptingProcess = object : MockNativeProcess() {
            override fun archPrctl(
                code: Int,
                addr: io.mazewall.ffi.memory.ManagedSegment,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (code == NativeConstants.ARCH_SHSTK_STATUS) {
                    addr.writeLong(0L, NativeConstants.ARCH_SHSTK_SHSTK)
                    statusWritten = true
                    return LinuxNative.SyscallResult.Success(0L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }
        }
        val customEngine = MockNativeEngine(process = interceptingProcess)
        LinuxNative.setEngine(customEngine)

        val status = Platform.queryIntelCetStatus()
        assertTrue(statusWritten)
        assertEquals(NativeConstants.ARCH_SHSTK_SHSTK, status)
    }

    @Test
    fun `armIntelCet fails fast when lockIntelCet is true but CET is unsupported under FallbackBehavior FAIL`() {
        val nonLinuxProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Windows"
        }
        Platform.setProvider(nonLinuxProvider)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).lockIntelCet().build()

        assertThrows<UnsupportedOperationException> {
            policy.install()
        }
    }

    @Test
    fun `armIntelCet fails fast on non-CET Linux when lockIntelCet is true under FallbackBehavior FAIL`() {
        val linuxAmd64Provider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getOsArch(): String = "amd64"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L) // EINVAL

            override fun getSeccompMode(): SeccompMode = SeccompMode.Filter
        }
        Platform.setProvider(linuxAmd64Provider)
        Platform.isCpuCetSupportedOverride = false

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).lockIntelCet().build()

        assertThrows<IllegalStateException> {
            policy.install()
        }
    }

    @Test
    fun `armIntelCet warns and bypasses under FallbackBehavior WARN_AND_BYPASS`() {
        val nonLinuxProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Windows"
        }
        Platform.setProvider(nonLinuxProvider)

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        try {
            val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).lockIntelCet().build()
            assertDoesNotThrow {
                policy.install().use { receipt ->
                    assertFalse(receipt.installed)
                    assertEquals(CetOutcome.UNCONFIRMED, receipt.cetOutcome)
                }
            }
        } finally {
            System.clearProperty("io.mazewall.fallback")
        }
    }

    @Test
    fun `armIntelCet warns and bypasses on non-CET Linux under FallbackBehavior WARN_AND_BYPASS`() {
        val linuxAmd64Provider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getOsArch(): String = "amd64"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L) // EINVAL

            override fun getSeccompMode(): SeccompMode = SeccompMode.Filter
        }
        Platform.setProvider(linuxAmd64Provider)
        Platform.isCpuCetSupportedOverride = false

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        try {
            val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).lockIntelCet().build()
            assertDoesNotThrow {
                policy.install().use { receipt ->
                    assertFalse(receipt.installed)
                    assertEquals(CetOutcome.UNCONFIRMED, receipt.cetOutcome)
                }
            }
        } finally {
            System.clearProperty("io.mazewall.fallback")
        }
    }

    @Test
    fun `armIntelCet locks and verifies pre-enabled shadow stack without late enablement`() {
        val linuxAmd64Provider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getOsArch(): String = "amd64"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L) // EINVAL

            override fun getSeccompMode(): SeccompMode = SeccompMode.Filter
        }
        Platform.setProvider(linuxAmd64Provider)

        var enabledCalled = false
        var lockedCalled = false
        var statusChecked = false
        var statusActive = true

        val interceptingProcess = object : MockNativeProcess() {
            override fun archPrctl(
                code: Int,
                addr: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (code == NativeConstants.ARCH_SHSTK_ENABLE && addr == NativeConstants.ARCH_SHSTK_SHSTK) {
                    enabledCalled = true
                    statusActive = true
                    return LinuxNative.SyscallResult.Success(0L)
                }
                if (code == NativeConstants.ARCH_SHSTK_LOCK && addr == NativeConstants.ARCH_SHSTK_SHSTK) {
                    lockedCalled = true
                    return LinuxNative.SyscallResult.Success(0L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }

            override fun archPrctl(
                code: Int,
                addr: io.mazewall.ffi.memory.ManagedSegment,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (code == NativeConstants.ARCH_SHSTK_STATUS) {
                    val valToWrite = if (statusActive) NativeConstants.ARCH_SHSTK_SHSTK else 0L
                    addr.writeLong(0L, valToWrite)
                    statusChecked = true
                    return LinuxNative.SyscallResult.Success(0L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }
        }

        val customEngine = MockNativeEngine(process = interceptingProcess)
        customEngine.process.prctlResult = LinuxNative.SyscallResult.Success(2L)
        LinuxNative.setEngine(customEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).lockIntelCet().build()
        assertDoesNotThrow {
            ContainedExecutors.installOnCurrentThread(policy)
        }

        assertFalse(enabledCalled)
        assertTrue(lockedCalled)
        assertTrue(statusChecked)
    }

    @Test
    fun `armIntelCet rejects lock EPERM even with enabled status`() {
        val linuxAmd64Provider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun getOsArch(): String = "amd64"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L) // EINVAL

            override fun getSeccompMode(): SeccompMode = SeccompMode.Filter
        }
        Platform.setProvider(linuxAmd64Provider)

        var enableCalled = false
        var lockCalled = false

        val interceptingProcess = object : MockNativeProcess() {
            override fun archPrctl(
                code: Int,
                addr: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (code == NativeConstants.ARCH_SHSTK_ENABLE) {
                    enableCalled = true
                    return LinuxNative.SyscallResult.Success(0L)
                }
                if (code == NativeConstants.ARCH_SHSTK_LOCK) {
                    lockCalled = true
                    return LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }

            override fun archPrctl(
                code: Int,
                addr: io.mazewall.ffi.memory.ManagedSegment,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (code == NativeConstants.ARCH_SHSTK_STATUS) {
                    addr.writeLong(0L, NativeConstants.ARCH_SHSTK_SHSTK)
                    return LinuxNative.SyscallResult.Success(0L)
                }
                return LinuxNative.SyscallResult.Success(0L)
            }
        }

        val customEngine = MockNativeEngine(process = interceptingProcess)
        customEngine.process.prctlResult = LinuxNative.SyscallResult.Success(2L)
        LinuxNative.setEngine(customEngine)

        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).lockIntelCet().build()
        assertThrows<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(policy)
        }

        assertFalse(enableCalled)
        assertTrue(lockCalled)
    }

    @Test
    fun `RealPlatformProvider probeCetSupported requires shstk and ignores override when cleared`() {
        Platform.isCpuCetSupportedOverride = null
        val provider = RealPlatformProvider
        val supported = provider.probeCetSupported()
        // Should execute without exception and return boolean matching actual CPU capability
        assertNotNull(supported)
    }
}
