package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockPlatformProvider
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.FileDescriptor
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LandlockApplyResultTest {

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `tryCreateRuleset returns Error on ENOSYS without throwing`() {
        LinuxNative.setEngine(object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: io.mazewall.core.NativeArg,
                a2: io.mazewall.core.NativeArg,
                a3: io.mazewall.core.NativeArg,
                a4: io.mazewall.core.NativeArg,
                a5: io.mazewall.core.NativeArg,
                a6: io.mazewall.core.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    return LinuxNative.SyscallResult.Error(NativeConstants.ENOSYS, -1)
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        })

        NativeArena.ofConfined().use { arena ->
            val result = with(arena) { Landlock.tryCreateRuleset(15L, 1) }
            val error = assertIs<LandlockFdOutcome.Err>(result)
            assertEquals(NativeConstants.ENOSYS, error.errno)
        }
    }

    @Test
    fun `tryEnforceRuleset returns Error on EPERM without throwing`() {
        LinuxNative.setEngine(object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: io.mazewall.core.NativeArg,
                a2: io.mazewall.core.NativeArg,
                a3: io.mazewall.core.NativeArg,
                a4: io.mazewall.core.NativeArg,
                a5: io.mazewall.core.NativeArg,
                a6: io.mazewall.core.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (nr == NativeConstants.LANDLOCK_RESTRICT_SELF_NR) {
                    return LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1)
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        })

        val ruleset = LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe(42))
        val result = Landlock.tryEnforceRuleset(ruleset, false)
        val error = assertIs<LandlockRestrictOutcome.Err>(result)
        assertEquals(NativeConstants.EPERM, error.errno)
    }

    @Test
    fun `tryApplyRuleset maps ENOSYS create to Rejected when fallback is FAIL`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        Platform.setProvider(MockPlatformProvider())
        LinuxNative.setEngine(enosysCreateEngine())

        val result = Landlock.tryApplyRuleset(Policy.PURE_COMPUTE_UNSAFE.definition)
        val rejected = assertIs<LandlockApplyResult.Rejected>(result)
        assertEquals(NativeConstants.ENOSYS, rejected.errno)
    }

    @Test
    fun `orThrow fail-closes Rejected and never treats it as applied`() {
        val rejected = LandlockApplyResult.Rejected("landlock_restrict_self", NativeConstants.EPERM)
        assertFailsWith<IllegalStateException> {
            rejected.orThrow()
        }
    }

    @Test
    fun `applyRuleset still fail-closes by unpacking tryApplyRuleset`(@TempDir dir: Path) {
        System.setProperty("io.mazewall.fallback", "FAIL")
        Platform.setProvider(MockPlatformProvider())
        LinuxNative.setEngine(enosysCreateEngine())

        assertFailsWith<UnsupportedKernelFeatureException> {
            Landlock.applyRuleset(
                Policy.builder().allowFsRead(dir.toString()).build().definition,
            )
        }
    }

    @Test
    fun `ContainedExecutors install receipt is not installed when landlock is rejected`(@TempDir dir: Path) {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        Platform.setProvider(MockPlatformProvider())
        LinuxNative.setEngine(enosysCreateEngine())

        val policy = Policy.builder().allowFsRead(dir.toString()).build()
        val receipt = ContainedExecutors.installOnCurrentThread(policy)
        assertTrue(!receipt.installed)
        assertFailsWith<IllegalStateException> {
            receipt.requireInstalled()
        }
    }

    private fun enosysCreateEngine(): MockNativeEngine = object : MockNativeEngine() {
        override fun syscall(
            nr: Long,
            a1: io.mazewall.core.NativeArg,
            a2: io.mazewall.core.NativeArg,
            a3: io.mazewall.core.NativeArg,
            a4: io.mazewall.core.NativeArg,
            a5: io.mazewall.core.NativeArg,
            a6: io.mazewall.core.NativeArg,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            if (nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                a1 is io.mazewall.core.NativeArg.MemoryArg &&
                a1.value != ManagedSegment.NULL
            ) {
                return LinuxNative.SyscallResult.Error(NativeConstants.ENOSYS, -1)
            }
            if (nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                return LinuxNative.SyscallResult.Success(5L)
            }
            return super.syscall(nr, a1, a2, a3, a4, a5, a6)
        }
    }
}
