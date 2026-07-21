package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeNetworking
import io.mazewall.MockNativeProcess
import io.mazewall.MockPlatformProvider
import io.mazewall.NativeTransaction
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SandboxedPath
import io.mazewall.ffi.memory.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import kotlin.test.*

class LandlockCoverageTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        System.clearProperty("io.mazewall.fallback")
    }

    private open class SupportedLandlockMock(
        fileSystem: MockNativeFileSystem = MockNativeFileSystem(),
        networking: MockNativeNetworking = MockNativeNetworking(),
        process: MockNativeProcess = MockNativeProcess(),
        memory: MockNativeMemory = MockNativeMemory()
    ) : MockNativeEngine(fileSystem, networking, process, memory) {
        context(_: NativeTransaction)
        override fun syscall(
            nr: Long,
            a1: io.mazewall.core.NativeArg,
            a2: io.mazewall.core.NativeArg,
            a3: io.mazewall.core.NativeArg,
            a4: io.mazewall.core.NativeArg,
            a5: io.mazewall.core.NativeArg,
            a6: io.mazewall.core.NativeArg,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
            if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                a3 is io.mazewall.core.NativeArg.LongArg && a3.value == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_VERSION
            ) {
                return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(5)
            }
            return super.syscall(nr, a1, a2, a3, a4, a5, a6)
        }
    }

    @Test
    fun `test handleUnsupportedLandlock with WARN_AND_BYPASS`() {
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        Landlock.handleUnsupportedLandlock()
    }

    @Test
    fun `test addRuleFollowSymlinks with open failure`() {
        val mock = SupportedLandlockMock()
        mock.fileSystem.openResult = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(13, -1) // EACCES
        LinuxNative.setEngine(mock)

        NativeArena.ofConfined().use { nativeArena ->
            with(nativeArena) {
                Landlock.addJvmClasspathRules(LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe<FileDescriptorRole.Ruleset>(42)), 0L)
            }
        }
    }

    @Test
    fun `test addRuleFollowSymlinks with addRule failure`() {
        val mock = object : SupportedLandlockMock() {
            context(_: NativeTransaction)
            override fun syscall(
                nr: Long,
                a1: io.mazewall.core.NativeArg,
                a2: io.mazewall.core.NativeArg,
                a3: io.mazewall.core.NativeArg,
                a4: io.mazewall.core.NativeArg,
                a5: io.mazewall.core.NativeArg,
                a6: io.mazewall.core.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_ADD_RULE_NR) {
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(1, -1) // EPERM
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        mock.fileSystem.openResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100)
        LinuxNative.setEngine(mock)

        NativeArena.ofConfined().use { nativeArena ->
            with(nativeArena) {
                Landlock.addJvmClasspathRules(LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe<FileDescriptorRole.Ruleset>(42)), 0L)
            }
        }
    }

    @Test
    fun `test restrictSelf failure`() {
        val mock = object : SupportedLandlockMock() {
            context(_: NativeTransaction)
            override fun syscall(
                nr: Long,
                a1: io.mazewall.core.NativeArg,
                a2: io.mazewall.core.NativeArg,
                a3: io.mazewall.core.NativeArg,
                a4: io.mazewall.core.NativeArg,
                a5: io.mazewall.core.NativeArg,
                a6: io.mazewall.core.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_RESTRICT_SELF_NR) {
                    return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(1, -1) // EPERM
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        LinuxNative.setEngine(mock)

        val session = LandlockSession(Policy.builder().build().definition)
        org.junit.jupiter.api.Assumptions.assumeTrue(io.mazewall.Platform.isSupported())
        assertFailsWith<IllegalStateException> {
            session.applyRuleset()
        }
    }

    @Test
    fun `test LandlockSession process-wide supported`() {
        val mockPlatform = MockPlatformProvider()
        mockPlatform.mockLandlockAbiVersion = 8
        Platform.setProvider(mockPlatform)

        val mockEngine = SupportedLandlockMock()
        LinuxNative.setEngine(mockEngine)

        val session = LandlockSession(Policy.builder().build().definition, processWide = true)
        session.applyRuleset()
    }

    @Test
    fun `test LandlockSession process-wide unsupported`() {
        val mockPlatform = MockPlatformProvider()
        mockPlatform.mockLandlockAbiVersion = 5
        Platform.setProvider(mockPlatform)

        val mockEngine = SupportedLandlockMock()
        LinuxNative.setEngine(mockEngine)

        // Should log warning and continue with thread-scoped
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        val session = LandlockSession(Policy.builder().build().definition, processWide = true)
        session.applyRuleset()
    }

    @Test
    fun `test restrictSelf coverage`() {
        val mock = SupportedLandlockMock()
        LinuxNative.setEngine(mock)

        // Default
        LandlockLifecycle.RulesAdded(LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe<FileDescriptorRole.Ruleset>(10))).restrictSelf()

        // Thread-scoped
        LandlockLifecycle.RulesAdded(LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe<FileDescriptorRole.Ruleset>(10))).restrictSelf(false)

        // Process-wide
        LandlockLifecycle.RulesAdded(LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe<FileDescriptorRole.Ruleset>(10))).restrictSelf(true)
    }

    @Test
    fun `test calculateFinalAccess branches`() {
        val mock = SupportedLandlockMock()
        mock.fileSystem.openResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100)
        LinuxNative.setEngine(mock)

        // Branch: isFallback = true
        // handleInitialOpenFailure returns true if errno is 2 (ENOENT)
        val calls = java.util.concurrent.atomic
            .AtomicInteger(0)
        val mockFallback = SupportedLandlockMock(
            fileSystem = object : MockNativeFileSystem() {
                context(_: NativeTransaction)
                override fun open(
                    path: ManagedSegment,
                    flags: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val current = calls.incrementAndGet()
                    return if (current == 1) {
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(2, -1)
                    } else {
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100)
                    }
                }
            }
        )
        LinuxNative.setEngine(mockFallback)
        val session1 = LandlockSession(Policy.builder().allowFsRead(SandboxedPath.of("/nonexistent/file", true)).build().definition)
        session1.applyRuleset()

        LinuxNative.setEngine(mock)
        val tempFile = java.io.File.createTempFile("landlock-test", "txt")
        try {
            val session2 = LandlockSession(Policy.builder().allowFsRead(tempFile.absolutePath).build().definition)
            session2.applyRuleset()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `test handleInitialOpenFailure with deleted file`() {
        val deletedPathAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val mockFallback = SupportedLandlockMock(
            fileSystem = object : MockNativeFileSystem() {
                context(_: NativeTransaction)
                override fun open(
                    path: ManagedSegment,
                    flags: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val pathStr = path.native.getString(0)
                    if (pathStr.contains(" (deleted)")) {
                        deletedPathAttempts.incrementAndGet()
                        return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(2, -1) // ENOENT
                    }
                    return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100)
                }
            }
        )
        LinuxNative.setEngine(mockFallback)

        // If path ends with " (deleted)", it should NOT call open a second time (meaning no fallback occurs)
        org.junit.jupiter.api.Assumptions.assumeTrue(io.mazewall.Platform.isSupported())
        val session = LandlockSession(Policy.builder().allowFsRead(SandboxedPath.of("/nonexistent/file (deleted)", true)).build().definition)
        session.applyRuleset()

        assertEquals(1, deletedPathAttempts.get(), "Should only attempt to open the deleted file path once, with no fallback to parent directory")
    }
}
