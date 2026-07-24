package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.UnsupportedKernelFeatureException
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
                    val pathStr = path.readString(0L)
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

    @Test
    fun `test restrictive barrier on unsupported landlock under fallback FAIL`() {
        val mockPlatform = MockPlatformProvider()
        mockPlatform.mockLandlockAbiVersion = 0
        Platform.setProvider(mockPlatform)

        System.setProperty("io.mazewall.fallback", "FAIL")
        assertFailsWith<UnsupportedOperationException> {
            Landlock.applyRestrictiveBarrier()
        }
    }

    @Test
    fun `test restrictive barrier on unsupported landlock under fallback WARN_AND_BYPASS`() {
        val mockPlatform = MockPlatformProvider()
        mockPlatform.mockLandlockAbiVersion = 0
        Platform.setProvider(mockPlatform)

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        // Should succeed by bypassing
        Landlock.applyRestrictiveBarrier()
    }

    @Test
    fun `test restrictive barrier on unsupported landlock under fallback SILENT_BYPASS`() {
        val mockPlatform = MockPlatformProvider()
        mockPlatform.mockLandlockAbiVersion = 0
        Platform.setProvider(mockPlatform)

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        // Should succeed silently
        Landlock.applyRestrictiveBarrier()
    }

    @Test
    fun `test validateAbiSupport under fallback SILENT_BYPASS`() {
        val mockPlatform = MockPlatformProvider()
        mockPlatform.mockLandlockAbiVersion = 1
        Platform.setProvider(mockPlatform)

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        // Request policy requiring ABI v2 (rename)
        val policy = Policy.builder().allowFsRead("/tmp").allow(io.mazewall.core.Syscall.RENAME).build()
        // Should run and bypass silently without warning or exception
        val accessMask = Landlock.getAccessMask(1, policy.definition)
        assertNotNull(accessMask)
    }

    @Test
    fun `test handleProcessWideUnsupported under fallback SILENT_BYPASS`() {
        val mockPlatform = MockPlatformProvider()
        mockPlatform.mockLandlockAbiVersion = 5
        Platform.setProvider(mockPlatform)

        val mockEngine = SupportedLandlockMock()
        LinuxNative.setEngine(mockEngine)

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val session = LandlockSession(Policy.builder().build().definition, processWide = true)
        // Should run and apply thread-scoped silently without warning or exception
        session.applyRuleset()
    }

    @Test
    fun `test handleUnsupportedLandlock throws UnsupportedKernelFeatureException under fallback FAIL`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        val ex = assertFailsWith<UnsupportedKernelFeatureException> {
            Landlock.handleUnsupportedLandlock()
        }
        assertTrue(ex.message!!.contains("Landlock is not supported on this kernel"))
        assertTrue(ex.message!!.contains("Linux kernel 5.13+"))
    }

    @Test
    fun `test createRuleset throws UnsupportedKernelFeatureException on ENOSYS`() {
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
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                    a1 is io.mazewall.core.NativeArg.MemoryArg &&
                    a1.value != ManagedSegment.NULL
                ) {
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.ENOSYS, -1)
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        LinuxNative.setEngine(mock)

        NativeArena.ofConfined().use { arena ->
            val ex = assertFailsWith<UnsupportedKernelFeatureException> {
                with(arena) {
                    Landlock.createRuleset(15L, 1)
                }
            }
            assertTrue(ex.message!!.contains("landlock_create_ruleset failed with ENOSYS"))
            assertTrue(ex.message!!.contains("requires Linux kernel 5.13+"))
        }
    }

    @Test
    fun `test createRuleset throws UnsupportedKernelFeatureException on EOPNOTSUPP`() {
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
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                    a1 is io.mazewall.core.NativeArg.MemoryArg &&
                    a1.value != ManagedSegment.NULL
                ) {
                    return LinuxNative.SyscallResult.Error(95, -1) // EOPNOTSUPP
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        LinuxNative.setEngine(mock)

        NativeArena.ofConfined().use { arena ->
            val ex = assertFailsWith<UnsupportedKernelFeatureException> {
                with(arena) {
                    Landlock.createRuleset(15L, 1)
                }
            }
            assertTrue(ex.message!!.contains("landlock_create_ruleset failed with EOPNOTSUPP"))
            assertTrue(ex.message!!.contains("requires Linux kernel 5.13+"))
        }
    }

    @Test
    fun `test enforceRuleset throws UnsupportedKernelFeatureException on ENOSYS`() {
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
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.ENOSYS, -1)
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        LinuxNative.setEngine(mock)

        val ruleset = LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe(42))
        val ex = assertFailsWith<UnsupportedKernelFeatureException> {
            Landlock.enforceRuleset(ruleset, false)
        }
        assertTrue(ex.message!!.contains("landlock_restrict_self failed with ENOSYS"))
        assertTrue(ex.message!!.contains("requires Linux kernel 5.13+"))
    }

    @Test
    fun `test addRuleToRuleset throws UnsupportedKernelFeatureException on ENOSYS`() {
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
                    return LinuxNative.SyscallResult.Error(io.mazewall.ffi.NativeConstants.ENOSYS, -1)
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        mock.fileSystem.openResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100)
        LinuxNative.setEngine(mock)

        NativeArena.ofConfined().use { arena ->
            val ruleset = LandlockRuleset<RulesetState.Building>(FileDescriptor.unsafe(42))
            val ex = assertFailsWith<UnsupportedKernelFeatureException> {
                with(arena) {
                    Landlock.addJvmClasspathRules(ruleset, 0L)
                }
            }
            assertTrue(ex.message!!.contains("landlock_add_rule failed with ENOSYS"))
            assertTrue(ex.message!!.contains("requires Linux kernel 5.13+"))
        }
    }

    @Test
    fun `test fallback parent open flags include O_PATH and O_CLOEXEC and exclude O_NOFOLLOW`() {
        val observedFlags = mutableListOf<Pair<String, Int>>()
        val mockFallback = SupportedLandlockMock(
            fileSystem = object : MockNativeFileSystem() {
                context(_: NativeTransaction)
                override fun open(
                    path: ManagedSegment,
                    flags: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val pathStr = path.readString(0L)
                    if (pathStr == "/nonexistent/file") {
                        observedFlags.add(pathStr to flags)
                        return LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(2, -1)
                    }
                    if (pathStr == "/nonexistent") {
                        observedFlags.add(pathStr to flags)
                        return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100)
                    }
                    return LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(100)
                }
            }
        )
        LinuxNative.setEngine(mockFallback)

        val session = LandlockSession(
            Policy.builder().allowFsRead(SandboxedPath.of("/nonexistent/file", true)).build().definition
        )
        session.applyRuleset()

        // Check that exactly two target open calls were made
        assertEquals(2, observedFlags.size)

        // First call should be for /nonexistent/file with O_PATH, O_CLOEXEC, and O_NOFOLLOW
        val (firstPath, firstFlags) = observedFlags[0]
        assertEquals("/nonexistent/file", firstPath)
        assertTrue((firstFlags and io.mazewall.ffi.NativeConstants.O_PATH) != 0, "First call must contain O_PATH")
        assertTrue((firstFlags and io.mazewall.ffi.NativeConstants.O_CLOEXEC) != 0, "First call must contain O_CLOEXEC")
        assertTrue((firstFlags and io.mazewall.ffi.NativeConstants.O_NOFOLLOW) != 0, "First call must contain O_NOFOLLOW")

        // Second call (fallback) should be for parent /nonexistent, must contain O_PATH, O_CLOEXEC, and MUST NOT contain O_NOFOLLOW
        val (secondPath, secondFlags) = observedFlags[1]
        assertEquals("/nonexistent", secondPath)
        assertTrue((secondFlags and io.mazewall.ffi.NativeConstants.O_PATH) != 0, "Fallback call must contain O_PATH")
        assertTrue((secondFlags and io.mazewall.ffi.NativeConstants.O_CLOEXEC) != 0, "Fallback call must contain O_CLOEXEC")
        assertTrue((secondFlags and io.mazewall.ffi.NativeConstants.O_NOFOLLOW) == 0, "Fallback call must NOT contain O_NOFOLLOW")
    }

    @Test
    fun `test net access mask calculation for ABI versions`() {
        // ABI < 4
        assertEquals(0L, Landlock.getFullNetAccessMask(3))
        val policyWithBlocks = Policy.builder().block(io.mazewall.core.Syscall.BIND, io.mazewall.core.Syscall.CONNECT).build()
        assertEquals(0L, Landlock.getNetAccessMask(3, policyWithBlocks.definition))

        // ABI >= 4 (getFullNetAccessMask)
        val fullNetMask = Landlock.LANDLOCK_ACCESS_NET_BIND_TCP or Landlock.LANDLOCK_ACCESS_NET_CONNECT_TCP
        assertEquals(fullNetMask, Landlock.getFullNetAccessMask(4))

        // ABI >= 4 (getNetAccessMask)
        // Scenario A: blocks both BIND and CONNECT
        val policyBlockBoth = Policy.builder().block(io.mazewall.core.Syscall.BIND, io.mazewall.core.Syscall.CONNECT).build()
        assertEquals(fullNetMask, Landlock.getNetAccessMask(4, policyBlockBoth.definition))

        // Scenario B: blocks only BIND
        val policyBlockBind = Policy.builder().block(io.mazewall.core.Syscall.BIND).build()
        assertEquals(Landlock.LANDLOCK_ACCESS_NET_BIND_TCP, Landlock.getNetAccessMask(4, policyBlockBind.definition))

        // Scenario C: blocks only CONNECT
        val policyBlockConnect = Policy.builder().block(io.mazewall.core.Syscall.CONNECT).build()
        assertEquals(Landlock.LANDLOCK_ACCESS_NET_CONNECT_TCP, Landlock.getNetAccessMask(4, policyBlockConnect.definition))

        // Scenario D: blocks neither
        val policyBlockNone = Policy.builder().build()
        assertEquals(0L, Landlock.getNetAccessMask(4, policyBlockNone.definition))
    }

    @Test
    fun `test createRuleset sizes and network masks`() {
        val observedSizes = mutableListOf<Long>()
        val observedNetMasks = mutableListOf<Long>()

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
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                    a1 is io.mazewall.core.NativeArg.MemoryArg &&
                    a1.value != ManagedSegment.NULL &&
                    a2 is io.mazewall.core.NativeArg.LongArg
                ) {
                    observedSizes.add(a2.value)
                    val rulesetAttr = LandlockRulesetAttrSegment(a1.value.unwrap)
                    observedNetMasks.add(rulesetAttr.getHandledAccessNet())
                    return LinuxNative.SyscallResult.Success(100L)
                }
                return super.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
        }
        LinuxNative.setEngine(mock)

        NativeArena.ofConfined().use { arena ->
            with(arena) {
                // Test ABI 3 (size should be 8L / V1 size, net mask is unused but let's check)
                Landlock.createRuleset(15L, Landlock.LANDLOCK_ACCESS_NET_BIND_TCP, 3)

                // Test ABI 4 (size should be 16L / V2 size, net mask should be passed correctly)
                Landlock.createRuleset(15L, Landlock.LANDLOCK_ACCESS_NET_CONNECT_TCP, 4)
            }
        }

        assertEquals(2, observedSizes.size)
        assertEquals(io.mazewall.ffi.Layouts.LANDLOCK_RULESET_ATTR_V1_SIZE, observedSizes[0])
        assertEquals(io.mazewall.ffi.Layouts.LANDLOCK_RULESET_ATTR_SIZE, observedSizes[1])

        assertEquals(Landlock.LANDLOCK_ACCESS_NET_CONNECT_TCP, observedNetMasks[1])
    }
}
