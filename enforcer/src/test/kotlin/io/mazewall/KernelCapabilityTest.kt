package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.ffi.NativeConstants
import io.mazewall.landlock.LandlockCapabilityProbe
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class KernelCapabilityTest {
    @AfterEach
    fun reset() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
    }

    @Test
    fun `Landlock classification distinguishes unsupported from rejected`() {
        assertEquals(
            KernelCapability.Unsupported,
            LandlockCapabilityProbe.classifyCapability(
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState>(NativeConstants.ENOSYS, -1L),
            ),
        )
        assertEquals(
            KernelCapability.Unsupported,
            LandlockCapabilityProbe.classifyCapability(
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState>(NativeConstants.EOPNOTSUPP, -1L),
            ),
        )
        assertEquals(
            KernelCapability.Rejected(NativeConstants.EPERM),
            LandlockCapabilityProbe.classifyCapability(
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState>(NativeConstants.EPERM, -1L),
            ),
        )
        assertEquals(
            KernelCapability.Available(6),
            LandlockCapabilityProbe.classifyCapability(LinuxNative.SyscallResult.Success(6L)),
        )
    }

    @Test
    fun `assessment preserves Landlock permission rejection`() {
        val nativeEngine = MockNativeEngine()
        nativeEngine.onSyscall =
            { nr: Long, _: NativeArg, _: NativeArg, _: NativeArg, _: NativeArg, _: NativeArg, _: NativeArg ->
                if (nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
                } else {
                    nativeEngine.syscallResult
                }
            }
        LinuxNative.setEngine(nativeEngine)
        Platform.setProvider(RealPlatformProvider)

        val policy =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowFsRead("/tmp")
                .build()

        val assessment = InstallationAssessor.assess(policy.definition, processWide = false)

        assertContains(assessment.blockingReasons, "landlock capability rejected with errno 1")
    }

    @Test
    fun `assessment describes unsupported Landlock capability explicitly`() {
        val assessment = assessLandlock(KernelCapability.Unsupported)

        assertContains(assessment.blockingReasons, "landlock capability is unsupported")
    }

    @Test
    fun `assessment retains Landlock probe failure cause`() {
        val failure = IllegalStateException("native binding unavailable")

        val assessment = assessLandlock(KernelCapability.ProbeFailed(failure))

        assertContains(assessment.blockingReasons, "landlock capability probe failed: native binding unavailable")
    }

    @Test
    fun `RealPlatformProvider preserves Landlock probe exception`() {
        val failure = IllegalStateException("Landlock probe broke")
        val nativeEngine = MockNativeEngine()
        nativeEngine.onSyscall = { _, _, _, _, _, _, _ -> throw failure }
        LinuxNative.setEngine(nativeEngine)

        val capability = RealPlatformProvider.getLandlockCapability()

        val probeFailed = assertIs<KernelCapability.ProbeFailed>(capability)
        assertSame(failure, probeFailed.cause)
        assertEquals(0, RealPlatformProvider.getLandlockAbiVersion())
    }

    private fun assessLandlock(capability: KernelCapability<Int>): InstallationAssessment {
        Platform.setProvider(
            object : MockPlatformProvider() {
                override fun getLandlockCapability(): KernelCapability<Int> = capability
            },
        )
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        return InstallationAssessor.assess(policy.definition, processWide = false)
    }
}
