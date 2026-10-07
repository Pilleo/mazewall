package io.mazewall.landlock

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CapturedLandlockBackendTest {
    enum class SwitchAt { SESSION_CAPTURE, RULESET_CREATE, ADD_RULE, PRIVILEGE_SETUP }

    @AfterEach
    fun restoreRuntime() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        System.clearProperty("io.mazewall.fallback")
    }

    @ParameterizedTest
    @EnumSource(SwitchAt::class)
    fun `ruleset lifetime uses selected native and capability snapshots`(switchAt: SwitchAt) {
        System.setProperty("io.mazewall.fallback", "FAIL")
        Platform.setProvider(provider(KernelCapability.Available(8)))
        val foreignCalls = AtomicInteger()
        val foreign = MockNativeEngine().apply {
            onSyscall = { _, _, _, _, _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
            process.onPrctl = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
            fileSystem.onOpen = { _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
            fileSystem.onClose = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        val restrictions = AtomicInteger()
        val closes = AtomicInteger()
        val selected = MockNativeEngine().apply {
            onSyscall = { nr, _, _, _, _, _, _ ->
                when (nr) {
                    444L -> {
                        if (switchAt == SwitchAt.RULESET_CREATE) LinuxNative.setEngine(foreign)
                        LinuxNative.SyscallResult.Success(1000L)
                    }
                    445L -> {
                        if (switchAt == SwitchAt.ADD_RULE) LinuxNative.setEngine(foreign)
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    446L -> {
                        restrictions.incrementAndGet()
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    else -> LinuxNative.SyscallResult.Success(0L)
                }
            }
            process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs && switchAt == SwitchAt.PRIVILEGE_SETUP) LinuxNative.setEngine(foreign)
                LinuxNative.SyscallResult.Success(0L)
            }
            fileSystem.onClose = {
                closes.incrementAndGet()
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(selected)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val session = LandlockSession(policy.definition)
        if (switchAt == SwitchAt.SESSION_CAPTURE) {
            LinuxNative.setEngine(foreign)
            Platform.setProvider(provider(KernelCapability.Unsupported))
        }

        assertIs<LandlockApplyResult.Applied>(session.tryApplyRuleset())
        assertEquals(1, restrictions.get())
        assertTrue(closes.get() > 0)
        assertEquals(0, foreignCalls.get(), "Ruleset/path operations and retirement must remain on the selected backend")
    }

    @Test
    fun `rejected capability never becomes unsupported ABI bypass`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        LinuxNative.setEngine(MockNativeEngine())
        Platform.setProvider(provider(KernelCapability.Rejected(13)))
        val result = LandlockSession().tryApplyRuleset()
        assertEquals(13, assertIs<LandlockApplyResult.Rejected>(result).errno)
    }

    @Test
    fun `probe failure retains its original cause under manual fallback`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        LinuxNative.setEngine(MockNativeEngine())
        val failure = IllegalStateException("capability probe failed")
        Platform.setProvider(provider(KernelCapability.ProbeFailed(failure)))
        assertSame(failure, assertIs<LandlockApplyResult.Rejected>(LandlockSession().tryApplyRuleset()).cause)
    }

    private fun provider(capability: KernelCapability<Int>): PlatformProvider =
        object : PlatformProvider by RealPlatformProvider {
        override fun getOsName(): String = "Linux"

        override fun getLandlockCapability(): KernelCapability<Int> = capability

        override fun getLandlockAbiVersion(): Int = capability.availableValueOrZero()

        override fun hasKernelSeccompSupport(): Boolean = true

        override fun probeSeccompTsync(): Boolean = true

        override fun probeSeccompUserNotif(): Boolean = true

        override fun probeCetSupported(): Boolean = false
    }
}
