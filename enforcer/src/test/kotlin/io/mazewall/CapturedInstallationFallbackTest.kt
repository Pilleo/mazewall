package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class CapturedInstallationFallbackTest {
    private lateinit var engine: MockNativeEngine

    @BeforeEach
    fun prepareRuntime() {
        engine = MockNativeEngine()
        engine.process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
        LinuxNative.setEngine(engine)
        Platform.setProvider(supportedProvider())
    }

    @AfterEach
    fun restoreRuntime() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `changing FAIL to bypass during native denial cannot change handling`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetSeccomp) {
                System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
                LinuxNative.SyscallResult.Error(1, -1L)
            } else {
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
        }
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) {
                System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
                LinuxNative.SyscallResult.Error(1, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())
        }
    }

    @Test
    fun `bypass selection and receipt evidence retain the entry configuration`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetSeccomp) {
                System.setProperty("io.mazewall.fallback", "FAIL")
                LinuxNative.SyscallResult.Error(1, -1L)
            } else {
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
        }
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) {
                System.setProperty("io.mazewall.fallback", "FAIL")
                LinuxNative.SyscallResult.Error(1, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }

        val receipt = ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())
        assertFalse(receipt.installed)
        assertEquals(Platform.FallbackBehavior.SILENT_BYPASS, receipt.fallback)
    }

    @Test
    fun `successful receipt records fallback selected before native calls`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetSeccomp) System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
            LinuxNative.SyscallResult.Success(0L)
        }

        ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()).use { receipt ->
            assertEquals(Platform.FallbackBehavior.FAIL, receipt.fallback)
        }
    }

    @Test
    fun `Landlock capability probing cannot switch a strict attempt into bypass`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        Platform.setProvider(object : PlatformProvider by supportedProvider() {
            override fun getLandlockCapability(): KernelCapability<Int> {
                System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
                return KernelCapability.Unsupported
            }
        })
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()

        assertFailsWith<UnsupportedKernelFeatureException> { ContainedExecutors.installOnCurrentThread(policy) }
    }

    @Test
    fun `bypass receipt retains irreversible progress and original verification failure`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        // Native application succeeds, then the mandatory filter-mode verification fails.
        engine.process.onPrctl = { LinuxNative.SyscallResult.Success(0L) }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertFalse(receipt.installed)
        assertEquals(InstallationProgress(false, landlockApplied = true, seccompApplied = true), receipt.progress)
        val failure = assertIs<PartialInstallationException>(receipt.failure)
        assertNotNull(failure.cause)
        val rejected = assertFailsWith<PartialInstallationException> { receipt.requireInstalled() }
        assertSame(failure, rejected)
    }

    @Test
    fun `unsupported Landlock bypass records its reason and zero applied stages`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        Platform.setProvider(object : PlatformProvider by supportedProvider() {
            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Unsupported
        })
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()

        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertFalse(receipt.installed)
        assertEquals(InstallationProgress(false, landlockApplied = false, seccompApplied = false), receipt.progress)
        assertNotNull(receipt.failure)
        val rejected = assertFailsWith<IllegalStateException> { receipt.requireInstalled() }
        assertSame(receipt.failure, rejected)
    }

    @Test
    fun `platform probing cannot switch strict fallback into bypass`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        Platform.setProvider(object : PlatformProvider by supportedProvider() {
            override fun getOsName(): String {
                System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
                return "unsupported-test-platform"
            }
        })

        assertFailsWith<UnsupportedOperationException> {
            ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())
        }
    }

    @Test
    fun `unsupported platform bypass retains its failure and empty progress`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        Platform.setProvider(object : PlatformProvider by supportedProvider() {
            override fun getOsName(): String = "unsupported-test-platform"
        })

        val receipt = ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build())

        assertEquals(Platform.FallbackBehavior.SILENT_BYPASS, receipt.fallback)
        assertEquals(InstallationProgress(false, landlockApplied = false, seccompApplied = false), receipt.progress)
        val failure = assertIs<UnsupportedOperationException>(receipt.failure)
        assertSame(failure, assertFailsWith<UnsupportedOperationException> { receipt.requireInstalled() })
    }

    private fun supportedProvider(): PlatformProvider =
        object : PlatformProvider by RealPlatformProvider {
        override fun getOsName(): String = "Linux"

        override fun hasKernelSeccompSupport(): Boolean = true

        override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

        override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(5)

        override fun getLandlockAbiVersion(): Int = 5

        override fun probeSeccompTsync(): Boolean = true

        override fun probeSeccompUserNotif(): Boolean = true

        override fun probeCetSupported(): Boolean = false
    }
}
