package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Policy
import io.mazewall.RealNativeEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import kotlin.test.*

class LandlockCoverageTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.setEngine(RealNativeEngine)
    }

    @Test
    fun `test handleUnsupportedLandlock with WARN_AND_BYPASS`() {
        // We can't easily mock Platform.configuredFallback() because it reads from System props
        // but we can set the system property.
        val old = System.getProperty("io.mazewall.fallback")
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        try {
            // Should not throw
            Landlock.handleUnsupportedLandlock()
        } finally {
            if (old != null) {
                System.setProperty("io.mazewall.fallback", old)
            } else {
                System.clearProperty("io.mazewall.fallback")
            }
        }
    }

    @Test
    fun `test addRuleFollowSymlinks with open failure`() {
        val mock = MockNativeEngine()
        mock.openResult = LinuxNative.SyscallResult(-1, 13) // EACCES
        LinuxNative.setEngine(mock)

        Arena.ofConfined().use { arena ->
            // Should log warning but not throw
            Landlock.addJvmClasspathRules(42, 0L, arena)
        }
    }

    @Test
    fun `test addRuleFollowSymlinks with addRule failure`() {
        val mock = object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: Any?,
                a2: Any?,
                a3: Any?,
                a4: Any?,
                a5: Any?,
                a6: Any?,
            ): LinuxNative.SyscallResult {
                // If it's landlock_add_rule
                return if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_ADD_RULE_NR) {
                    LinuxNative.SyscallResult(-1, 22) // EINVAL
                } else {
                    super.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }
        }
        LinuxNative.setEngine(mock)

        Arena.ofConfined().use { arena ->
            // Should log warning but not throw
            Landlock.addJvmClasspathRules(42, 0L, arena)
        }
    }

    @Test
    fun `test enforceRuleset with prctl failure`() {
        val mock = MockNativeEngine()
        mock.prctlResult = LinuxNative.SyscallResult(-1, 1) // EPERM
        LinuxNative.setEngine(mock)

        assertFailsWith<IllegalStateException> {
            Landlock.enforceRuleset(42)
        }
    }

    @Test
    fun `test enforceRuleset with restrict_self failure`() {
        val mock = object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: Any?,
                a2: Any?,
                a3: Any?,
                a4: Any?,
                a5: Any?,
                a6: Any?,
            ): LinuxNative.SyscallResult {
                return if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_RESTRICT_SELF_NR) {
                    LinuxNative.SyscallResult(-1, 1) // EPERM
                } else {
                    super.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }
        }
        LinuxNative.setEngine(mock)

        assertFailsWith<IllegalStateException> {
            Landlock.enforceRuleset(42)
        }
    }

    @Test
    fun `test LandlockSession applyRuleset with create_ruleset failure`() {
        val mock = object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: Any?,
                a2: Any?,
                a3: Any?,
                a4: Any?,
                a5: Any?,
                a6: Any?,
            ): LinuxNative.SyscallResult {
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    // If it's the ABI version check (a3 is version flag)
                    if (a3 == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_VERSION) {
                        return LinuxNative.SyscallResult(5, 0)
                    }
                    return LinuxNative.SyscallResult(-1, 1) // EPERM for actual creation
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        LinuxNative.setEngine(mock)

        val session = LandlockSession(Policy.builder().allowFsRead("/tmp").build())
        assertFailsWith<IllegalStateException> {
            session.applyRuleset()
        }
        assertTrue(session.state is LandlockState.Failed)
    }

    @Test
    fun `test addRuleToRulesetAndVerify with non-EINVAL failure`() {
        val mock = object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: Any?,
                a2: Any?,
                a3: Any?,
                a4: Any?,
                a5: Any?,
                a6: Any?,
            ): LinuxNative.SyscallResult {
                // If it's the ABI version check
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR && a3 == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_VERSION) {
                    return LinuxNative.SyscallResult(5, 0)
                }
                // If it's create ruleset
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    return LinuxNative.SyscallResult(100, 0)
                }
                // If it's landlock_add_rule
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_ADD_RULE_NR) {
                    return LinuxNative.SyscallResult(-1, 1) // EPERM (not EINVAL)
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        LinuxNative.setEngine(mock)

        val session = LandlockSession(Policy.builder().allowFsRead("/tmp").build())
        assertFailsWith<IllegalStateException> {
            session.applyRuleset()
        }
    }
}
