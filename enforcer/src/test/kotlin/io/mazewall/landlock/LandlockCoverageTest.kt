package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.NativeTransaction
import io.mazewall.Policy
import io.mazewall.core.SandboxedPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import kotlin.test.*

class LandlockCoverageTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    private open class SupportedLandlockMock : MockNativeEngine() {
        context(_: NativeTransaction)
        override fun syscall(
            nr: Long,
            a1: Any?,
            a2: Any?,
            a3: Any?,
            a4: Any?,
            a5: Any?,
            a6: Any?,
        ): LinuxNative.SyscallResult {
            if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                a3 == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_VERSION
            ) {
                return LinuxNative.SyscallResult.Success(5)
            }
            return super.syscall(nr, a1, a2, a3, a4, a5, a6)
        }
    }

    @Test
    fun `test handleUnsupportedLandlock with WARN_AND_BYPASS`() {
        val old = System.getProperty("io.mazewall.fallback")
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        try {
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
        val mock = SupportedLandlockMock()
        mock.openResult = LinuxNative.SyscallResult.Error(13, -1) // EACCES
        LinuxNative.setEngine(mock)

        Arena.ofConfined().use { arena ->
            with(arena) {
                Landlock.addJvmClasspathRules(LinuxNative.FileDescriptor(42), 0L)
            }
        }
    }

    @Test
    fun `test addRuleFollowSymlinks with addRule failure`() {
        val mock = object : SupportedLandlockMock() {
            context(_: NativeTransaction)
            override fun syscall(
                nr: Long,
                a1: Any?,
                a2: Any?,
                a3: Any?,
                a4: Any?,
                a5: Any?,
                a6: Any?,
            ): LinuxNative.SyscallResult {
                return if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_ADD_RULE_NR) {
                    LinuxNative.SyscallResult.Error(22, -1) // EINVAL
                } else {
                    super.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }
        }
        LinuxNative.setEngine(mock)

        Arena.ofConfined().use { arena ->
            with(arena) {
                Landlock.addJvmClasspathRules(LinuxNative.FileDescriptor(42), 0L)
            }
        }
    }

    @Test
    fun `test enforceRuleset with prctl failure`() {
        val mock = SupportedLandlockMock()
        mock.prctlResult = LinuxNative.SyscallResult.Error(1, -1) // EPERM
        LinuxNative.setEngine(mock)

        assertFailsWith<IllegalStateException> {
            Landlock.enforceRuleset(LinuxNative.FileDescriptor(42))
        }
    }

    @Test
    fun `test LandlockSession applyRuleset with create_ruleset failure`() {
        val mock = SupportedLandlockMock()
        // Simulate create_ruleset failure by overriding syscall
        val mockFail = object : SupportedLandlockMock() {
            context(_: NativeTransaction)
            override fun syscall(
                nr: Long,
                a1: Any?,
                a2: Any?,
                a3: Any?,
                a4: Any?,
                a5: Any?,
                a6: Any?,
            ): LinuxNative.SyscallResult {
                return if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                    a3 == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_VERSION
                ) {
                    LinuxNative.SyscallResult.Success(5) // Allow version check
                } else if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    LinuxNative.SyscallResult.Error(1, -1) // Fail actual creation
                } else {
                    super.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }
        }
        LinuxNative.setEngine(mockFail)

        val session = LandlockSession(Policy.builder().allowFsRead(SandboxedPath.of("/tmp", true)).build())
        assertFailsWith<IllegalStateException> {
            session.applyRuleset()
        }
    }

    @Test
    fun `test logOpenFailure with ERRNO_ELOOP`() {
        val mock = SupportedLandlockMock()
        mock.openResult = LinuxNative.SyscallResult.Error(40, -1) // ELOOP
        LinuxNative.setEngine(mock)

        val session = LandlockSession(Policy.builder().allowFsRead(SandboxedPath.of("/tmp/link", true)).build())
        session.applyRuleset()
    }

    @Test
    fun `test calculateFinalAccess branches`() {
        val mock = SupportedLandlockMock()
        mock.openResult = LinuxNative.SyscallResult.Success(100)
        LinuxNative.setEngine(mock)

        // Branch: isFallback = true
        // handleInitialOpenFailure returns true if errno is 2 (ENOENT)
        val calls = java.util.concurrent.atomic
            .AtomicInteger(0)
        val mockFallback = object : SupportedLandlockMock() {
            context(_: NativeTransaction)
            override fun open(
                path: java.lang.foreign.MemorySegment,
                flags: Int,
            ): LinuxNative.SyscallResult {
                val current = calls.incrementAndGet()
                return if (current == 1) {
                    LinuxNative.SyscallResult.Error(2, -1)
                } else {
                    LinuxNative.SyscallResult.Success(100)
                }
            }
        }
        LinuxNative.setEngine(mockFallback)
        val session1 = LandlockSession(Policy.builder().allowFsRead(SandboxedPath.of("/nonexistent/file", true)).build())
        session1.applyRuleset()

        LinuxNative.setEngine(mock)
        val tempFile = java.io.File.createTempFile("landlock-test", "txt")
        try {
            val session2 = LandlockSession(Policy.builder().allowFsRead(tempFile.absolutePath).build())
            session2.applyRuleset()
        } finally {
            tempFile.delete()
        }
    }
}
