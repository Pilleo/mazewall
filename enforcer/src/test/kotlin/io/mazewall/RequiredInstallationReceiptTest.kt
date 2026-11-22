package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RequiredInstallationReceiptTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `installed seccomp alone cannot satisfy requested filesystem restriction`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val receipt = InstallationReceipt(false, policy.definition, installed = true, landlockApplied = false)

        assertFailsWith<IllegalStateException> { receipt.requireInstalled() }
    }

    @Test
    fun `requested filesystem restriction and seccomp together satisfy required installation`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val receipt = InstallationReceipt(false, policy.definition, installed = true, landlockApplied = true)

        assertSame(receipt, receipt.requireInstalled())
    }

    @Test
    fun `filesystem restriction alone cannot satisfy failed seccomp installation`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val receipt = InstallationReceipt(false, policy.definition, installed = false, landlockApplied = true)

        assertFailsWith<IllegalStateException> { receipt.requireInstalled() }
    }

    @Test
    fun `partial irreversible progress preserves applied landlock and original failure cause`() {
        val policy =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .filesystem(FileSystemPolicy.DenyAll)
                .lockIntelCet()
                .build()
        val rootCause = IllegalStateException("seccomp PR_SET_SECCOMP rejected")
        val progress = InstallationProgress(false, landlockApplied = true, seccompApplied = false)
        val failure = PartialInstallationException(progress, rootCause)
        val receipt =
            InstallationReceipt(
                processWide = false,
                requestedPolicy = policy.definition,
                installed = false,
                fallback = Platform.FallbackBehavior.FAIL,
                landlockApplied = true,
                progress = progress,
                failure = failure,
                cetOutcome = CetOutcome.UNCONFIRMED,
            )

        assertFalse(receipt.installed)
        assertTrue(receipt.landlockApplied)
        assertEquals(progress, receipt.progress)
        assertSame(failure, receipt.failure)
        val thrown = assertFailsWith<PartialInstallationException> { receipt.requireInstalled() }
        assertSame(failure, thrown)
        assertSame(rootCause, thrown.cause)
    }

    @Test
    fun `recorded failure takes precedence over landlock and cet checks in requireInstalled`() {
        val policy =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .filesystem(FileSystemPolicy.DenyAll)
                .lockIntelCet()
                .build()
        val sentinel = UnsupportedOperationException("recorded sentinel failure")
        val receipt =
            InstallationReceipt(
                processWide = false,
                requestedPolicy = policy.definition,
                installed = false,
                fallback = Platform.FallbackBehavior.FAIL,
                landlockApplied = false,
                failure = sentinel,
                cetOutcome = CetOutcome.UNCONFIRMED,
            )

        val thrown = assertFailsWith<UnsupportedOperationException> { receipt.requireInstalled() }
        assertSame(sentinel, thrown)
    }

    @Test
    fun `warn and bypass after landlock apply and seccomp failure yields Failed outcome with partial progress`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"

                override fun getLandlockAbiVersion(): Int = 5

                override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(5)

                override fun hasKernelSeccompSupport(): Boolean = true

                override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)
            },
        )
        val engine =
            MockNativeEngine().apply {
                val seccompNr = Arch.current().seccompSyscallNumber.toLong()
                process.onPrctl = { command ->
                    if (command is PrctlCommand.SetSeccomp) {
                        LinuxNative.SyscallResult.Error(22, -1L)
                    } else {
                        LinuxNative.SyscallResult.Success(0L)
                    }
                }
                onSyscall = { nr, _, _, _, _, _, _ ->
                    if (nr == seccompNr) {
                        LinuxNative.SyscallResult.Error(22, -1L)
                    } else {
                        LinuxNative.SyscallResult.Success(0L)
                    }
                }
            }
        LinuxNative.setEngine(engine)

        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        val receipt = ContainedExecutors.installOnCurrentThread(policy)

        assertIs<InstallationReceipt.Outcome.Failed>(receipt.outcome)
        assertFalse(receipt.installed)
        assertTrue(receipt.landlockApplied)
        assertEquals(InstallationProgress(false, landlockApplied = true, seccompApplied = false), receipt.progress)
        val failure = assertIs<PartialInstallationException>(receipt.failure)
        assertSame(failure, assertFailsWith<PartialInstallationException> { receipt.requireInstalled() })
    }

    @Test
    fun `bypassed narrower landlock preserves prior irreversible landlock and yields Bypassed outcome`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        val parentPolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        ContainmentStateRegistry.threadState = ContainerState().withLandlockPolicy(parentPolicy.definition)

        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"

                override fun getLandlockAbiVersion(): Int = 0

                override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Unsupported

                override fun hasKernelSeccompSupport(): Boolean = true

                override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)
            },
        )
        LinuxNative.setEngine(MockNativeEngine())

        val narrowerPolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).filesystem(FileSystemPolicy.DenyAll).build()
        val receipt = ContainedExecutors.installOnCurrentThread(narrowerPolicy)

        assertIs<InstallationReceipt.Outcome.Bypassed>(receipt.outcome)
        assertFalse(receipt.installed)
        assertTrue(receipt.landlockApplied)
        assertEquals(InstallationProgress(false, landlockApplied = false, seccompApplied = false), receipt.progress)
        val reason = assertIs<IllegalStateException>(receipt.failure)
        assertSame(reason, assertFailsWith<IllegalStateException> { receipt.requireInstalled() })
    }
}
