package io.mazewall.landlock

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.containmentFailureForCaller
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.testAcquisition
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LandlockTest : BaseIntegrationTest() {
    fun testReadAllowed(
        dir: String,
        file: String,
    ) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(dir)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
        val res = executor
            .submit(
            java.util.concurrent.Callable {
            ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                receipt.requireInstalled()
                Files.readString(Path.of(file))
            }
        },
        ).get(10, TimeUnit.SECONDS)
        if (res != "secret") throw IllegalStateException("Wrong content: $res")
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testReadBlocked(dir: String) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(dir)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit(
                java.util.concurrent.Callable {
                ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                    receipt.requireInstalled()
                    runCatching { Files.readString(Path.of("/etc/passwd")) }.getOrElse { throw containmentFailureForCaller(it) }
                }
            },
            ).get(10, TimeUnit.SECONDS)
            throw IllegalStateException("Should have failed")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause !is AccessDeniedException && cause !is ContainmentViolationException) throw e
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testWriteAllowed(
        dir: String,
        file: String,
    ) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsWrite(dir)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
        executor
            .submit {
            ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                receipt.requireInstalled()
                Files.writeString(Path.of(file), "hello")
            }
        }.get(10, TimeUnit.SECONDS)
        if (Files.readString(Path.of(file)) != "hello") throw IllegalStateException("Write failed")
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testWriteBlocked(
        dir: String,
        file: String,
    ) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(dir)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                    receipt.requireInstalled()
                    runCatching { Files.writeString(Path.of(file), "hello") }.getOrElse { throw containmentFailureForCaller(it) }
                }
            }.get(10, TimeUnit.SECONDS)
            throw IllegalStateException("Should have failed")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause !is AccessDeniedException && cause !is ContainmentViolationException) throw e
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testUnconstrained() {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFutureDenied {
                executor
                    .submit {
                    ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                        receipt.requireInstalled()
                        runCatching { Files.readString(Path.of("/etc/passwd")) }.getOrElse { throw containmentFailureForCaller(it) }
                    }
                }.get(10, TimeUnit.SECONDS)
            }
            Files.readString(Path.of("/etc/passwd")) // Main thread should succeed
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testStackingRecycled(
        dir: String,
        file: String,
    ) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(dir)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
        for (i in 1..20) {
            val res = executor
                .submit(
                java.util.concurrent.Callable {
                ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                    receipt.requireInstalled()
                    Files.readString(Path.of(file))
                }
            },
            ).get(10, TimeUnit.SECONDS)
            if (res != "data") throw IllegalStateException("Task $i failed")
        }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testExecBlocked(dir: String) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allowJvmClasspath()
            .allowFsRead(dir)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFutureDenied {
                executor
                    .submit(
                    java.util.concurrent.Callable {
                    ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                        receipt.requireInstalled()
                        runCatching { ProcessBuilder("/bin/echo", "fail").start() }.getOrElse { throw containmentFailureForCaller(it) }
                    }
                },
                ).get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testNonExistentFallback(file: String) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsWrite(
                io.mazewall.platform.lifecycle.SandboxedPath
                .of(file, true),
            ).build()
        val executor = Executors.newSingleThreadExecutor()
        try {
        executor
            .submit {
            ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                receipt.requireInstalled()
                Files.writeString(Path.of(file), "created!")
            }
        }.get(10, TimeUnit.SECONDS)
        if (Files.readString(Path.of(file)) != "created!") throw IllegalStateException("Write failed")
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testSymlinkRejection(
        realFile: String,
        symlink: String,
    ) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(symlink)
            .build()
        val executor = Executors.newSingleThreadExecutor()

        // The policy allows 'symlink', but Landlock rejects symlinks in rulesets (O_NOFOLLOW).
        // Therefore, access to the real file should be denied.
        try {
            executor
                .submit(
                java.util.concurrent.Callable {
                ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                    receipt.requireInstalled()
                    runCatching { Files.readString(Path.of(realFile)) }.getOrElse { throw containmentFailureForCaller(it) }
                }
            },
            ).get(10, TimeUnit.SECONDS)
            throw IllegalStateException("Access should have been denied because the symlink rule was rejected")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause !is AccessDeniedException && cause !is ContainmentViolationException) throw e
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testAutoClasspath(
        dir: String,
        file: String,
    ) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowFsRead(dir)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
        val res = executor
            .submit(
            java.util.concurrent.Callable {
            ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                receipt.requireInstalled()
                Files.readString(Path.of(file))
            }
        },
        ).get(10, TimeUnit.SECONDS)
        if (res != "auto-cp-ok") throw IllegalStateException("Wrong content: $res")
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testNestedSymlinks(realFile: String) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(realFile)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
        val res = executor
            .submit(
            java.util.concurrent.Callable {
            ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                receipt.requireInstalled()
                Files.readString(Path.of(realFile))
            }
        },
        ).get(10, TimeUnit.SECONDS)
        if (res != "nested-secret") throw IllegalStateException("Wrong content: $res")
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testDotDotTraversal(allowed: String) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(allowed)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFutureDenied {
                executor
                    .submit(
                    java.util.concurrent.Callable {
                    ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                        receipt.requireInstalled()
                        runCatching { Files.readString(Path.of(allowed).resolve("../forbidden/secret.txt")) }.getOrElse { throw containmentFailureForCaller(it) }
                    }
                },
                ).get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    fun testCircularSymlinks(linkA: String) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(linkA)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
        val res = executor
            .submit(
                java.util.concurrent.Callable {
                    ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                    receipt.requireInstalled()
                    try {
                        Files.readString(Path.of(linkA))
                        "success"
                    } catch (
                         _: java.io.IOException,
                    ) {
                        "eloop"
                    }
                    }
                },
            ).get(10, TimeUnit.SECONDS)
        if (res != "eloop") throw IllegalStateException("Expected eloop, got $res")
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockReadAllowedPath`() {
        val tempDir = createTempDirectory("landlock_test_allowed")
        val testFile = tempDir.resolve("test.txt")
        testFile.writeText("secret")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testReadAllowed", tempDir.toString(), testFile.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockReadBlockedPath`() {
        val tempDir = createTempDirectory("landlock_test_allowed")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testReadBlocked", tempDir.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockWriteAllowedPath`() {
        val tempDir = createTempDirectory("landlock_test_write_allowed")
        val testFile = tempDir.resolve("test.txt")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testWriteAllowed", tempDir.toString(), testFile.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockWriteBlockedPath`() {
        val tempDir = createTempDirectory("landlock_test_read_only")
        val testFile = tempDir.resolve("test.txt")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testWriteBlocked", tempDir.toString(), testFile.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockUnconstrainedThreadUnaffected`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testUnconstrained")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockRulesetNotStackedOnRecycledThread`() {
        if (!Landlock.isSupported()) return
        val tempDir = createTempDirectory("landlock_stacking_test")
        val testFile = tempDir.resolve("test.txt")
        testFile.writeText("data")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testStackingRecycled", tempDir.toString(), testFile.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockBlocksExecuteOutsideAllowedPaths`() {
        if (!Landlock.isSupported()) return
        val tempDir = createTempDirectory("landlock_exec_test")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testExecBlocked", tempDir.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockAllowWriteToNonExistentFileUsesParentDir`() {
        if (!Landlock.isSupported()) return
        val tempDir = createTempDirectory("landlock_nonexist_test")
        val newFile = tempDir.resolve("does_not_exist_yet.txt")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testNonExistentFallback", newFile.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockRejectsSymlinksInPolicy`() {
        if (!Landlock.isSupported()) return
        val realDir = createTempDirectory("landlock_real_target")
        val realFile = realDir.resolve("secret.txt")
        realFile.writeText("real-content")
        val symlinkDir = createTempDirectory("landlock_symlink_holder")
        val symlink = symlinkDir.resolve("link_to_real")
        Files.createSymbolicLink(symlink, realDir)
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testSymlinkRejection", realFile.toString(), symlink.toString())
        } finally {
            realDir.toFile().deleteRecursively()
            symlinkDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testContainmentWorksWithoutExplicitAllowJvmClasspath`() {
        if (!Landlock.isSupported()) return
        val tempDir = createTempDirectory("landlock_auto_cp_test")
        val testFile = tempDir.resolve("test.txt")
        testFile.writeText("auto-cp-ok")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testAutoClasspath", tempDir.toString(), testFile.toString())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `testLandlockAbiGapFailClosed_Rename`() {
        if (!Landlock.isSupported()) return
        val abi = Landlock.getAbiVersion()
        if (abi >= 2) return
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).unblock(Syscall.RENAME).build()
        val ex = assertFailsWith<UnsupportedOperationException> { Landlock.applyRuleset(policy.definition) }
        assertTrue(assertNotNull(ex.message).contains("Policy allows rename/link syscalls, but this kernel"))
    }

    @Test
    fun `testLandlockAbiGapFailClosed_Truncate`() {
        if (!Landlock.isSupported()) return
        val abi = Landlock.getAbiVersion()
        if (abi >= 3) return
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).unblock(Syscall.TRUNCATE).build()
        val ex = assertFailsWith<UnsupportedOperationException> { Landlock.applyRuleset(policy.definition) }
        assertTrue(assertNotNull(ex.message).contains("Policy allows truncate syscalls, but this kernel"))
    }

    @Test
    fun `testLandlockAbiGapFailClosed_Ioctl`() {
        if (!Landlock.isSupported()) return
        val abi = Landlock.getAbiVersion()
        if (abi >= 5) return
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).unblock(Syscall.IOCTL).build()
        val ex = assertFailsWith<UnsupportedOperationException> { Landlock.applyRuleset(policy.definition) }
        assertTrue(assertNotNull(ex.message).contains("Policy allows ioctl, but this kernel"))
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockNestedSymlinks`() {
        if (!Landlock.isSupported()) return
        val realDir = createTempDirectory("landlock_real_target")
        val realFile = realDir.resolve("secret.txt")
        realFile.writeText("nested-secret")
        val link1 = createTempDirectory("landlock_link1").resolve("l1")
        val link2 = createTempDirectory("landlock_link2").resolve("l2")
        Files.createSymbolicLink(link1, realDir)
        Files.createSymbolicLink(link2, link1)
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testNestedSymlinks", realFile.toString())
        } finally {
            realDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockDotDotTraversal`() {
        if (!Landlock.isSupported()) return
        val baseDir = createTempDirectory("landlock_base")
        val allowedSub = Files.createDirectory(baseDir.resolve("allowed"))
        val forbiddenSub = Files.createDirectory(baseDir.resolve("forbidden"))
        forbiddenSub.resolve("secret.txt").writeText("forbidden")
        allowedSub.resolve("ok.txt").writeText("ok")
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testDotDotTraversal", allowedSub.toString())
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockCircularSymlinksFailGracefully`() {
        if (!Landlock.isSupported()) return
        val dir = createTempDirectory("landlock_circular")
        val linkA = dir.resolve("linkA")
        val linkB = dir.resolve("linkB")
        Files.createSymbolicLink(linkA, linkB)
        Files.createSymbolicLink(linkB, linkA)
        try {
            IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testCircularSymlinks", linkA.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `testLandlockSessionStateTransitions`() {
        val mockEngine = object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: io.mazewall.platform.syscall.NativeArg,
                a2: io.mazewall.platform.syscall.NativeArg,
                a3: io.mazewall.platform.syscall.NativeArg,
                a4: io.mazewall.platform.syscall.NativeArg,
                a5: io.mazewall.platform.syscall.NativeArg,
                a6: io.mazewall.platform.syscall.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                    a3.asLong == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_VERSION
                        .toLong()
                ) {
                    LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(5) // ABI version 5
                } else if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(42).testAcquisition() // Fake ruleset FD
                } else {
                    LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0) // Success for other syscalls
                }
            }
        }
        LinuxNative.setEngine(mockEngine)
        try {
            val session = LandlockSession(Policy.PURE_COMPUTE_UNSAFE.definition)
            assertTrue(session.state is LandlockState.Uninitialized)
            session.applyRuleset()
            assertTrue(session.state is LandlockState.Applied)
        } finally {
            LinuxNative.setEngine(RealNativeEngine)
        }
    }

    @Test
    fun `testLandlockSessionFailedState`() {
        val mockEngine = object : MockNativeEngine() {
            override fun syscall(
                nr: Long,
                a1: io.mazewall.platform.syscall.NativeArg,
                a2: io.mazewall.platform.syscall.NativeArg,
                a3: io.mazewall.platform.syscall.NativeArg,
                a4: io.mazewall.platform.syscall.NativeArg,
                a5: io.mazewall.platform.syscall.NativeArg,
                a6: io.mazewall.platform.syscall.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                return if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR &&
                    a3.asLong == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_VERSION
                        .toLong()
                ) {
                    LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(5) // ABI version 5
                } else {
                    LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(22, -1) // EINVAL for ruleset creation
                }
            }
        }
        LinuxNative.setEngine(mockEngine)
        try {
            val session = LandlockSession(Policy.PURE_COMPUTE_UNSAFE.definition)
            assertFailsWith<IllegalStateException> {
                session.applyRuleset()
            }
            assertTrue(session.state is LandlockState.Failed)
        } finally {
            LinuxNative.setEngine(RealNativeEngine)
        }
    }

    fun testDirectoryRenameResistance(
        baseDir: String,
        allowedDir: String,
        forbiddenDir: String,
    ) {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .allowJvmClasspath()
            .allowFsRead(allowedDir)
            .build()
        val executor = Executors.newSingleThreadExecutor()

        val allowedPath = Path.of(allowedDir)
        val forbiddenPath = Path.of(forbiddenDir)
        val basePath = Path.of(baseDir)

        // 1. Initial reads before rename:
        try {
        executor
            .submit {
            ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
            receipt.requireInstalled()
            // Read allowedDir/allowed.txt should succeed
            val allowedTxt = allowedPath.resolve("allowed.txt")
            if (Files.readString(allowedTxt) != "allowed") {
                throw IllegalStateException("Failed to read allowed file initially")
            }

            // Read forbiddenDir/secret.txt should fail
            val forbiddenTxt = forbiddenPath.resolve("secret.txt")
            assertForbiddenReadDenied { Files.readString(forbiddenTxt) }
            }
        }.get(10, TimeUnit.SECONDS)

        // 2. Perform the rename on the main thread (unrestricted):
        val allowedRenamedPath = basePath.resolve("allowed_renamed")
        Files.move(allowedPath, allowedRenamedPath)
        Files.move(forbiddenPath, allowedPath)

        // 3. Post-rename reads:
        executor
            .submit {
            ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
            receipt.requireInstalled()
            // Attempt to read the new files at the old path allowedDir/secret.txt (which points to the forbidden directory)
            // This MUST be blocked because the inode of the new allowedDir was never allowed.
            val newSecretInAllowedOldPath = allowedPath.resolve("secret.txt")
            assertForbiddenReadDenied { Files.readString(newSecretInAllowedOldPath) }

            // Attempt to read allowed.txt in the renamed allowed directory (allowed_renamed/allowed.txt)
            // This MUST succeed because Landlock follows the inode, even though its path name was renamed.
            val allowedInRenamedPath = allowedRenamedPath.resolve("allowed.txt")
            if (Files.readString(allowedInRenamedPath) != "allowed") {
                throw IllegalStateException("Failed to read allowed file after rename")
            }
            }
        }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `Future denial boundary rejects successful completion`() {
        assertFailsWith<AssertionError> { assertFutureDenied { "unrestricted" } }
    }

    @Test
    fun `Future denial boundary preserves unrelated failure identity`() {
        val original = ExecutionException(IllegalStateException("unrelated Future failure"))
        assertSame(original, assertFailsWith<ExecutionException> { assertFutureDenied { throw original } })
    }

    @Test
    fun `Future denial boundary promotes fatal cause`() {
        val fatal = LinkageError("fatal Future failure")
        assertSame(fatal, assertFailsWith<LinkageError> { assertFutureDenied { throw ExecutionException(fatal) } })
    }

    @Test
    fun `Future denial boundary preserves interruption`() {
        val interrupted = InterruptedException("interrupted Future failure")
        try {
            assertSame(interrupted, assertFailsWith<InterruptedException> { assertFutureDenied { throw ExecutionException(interrupted) } })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `Future denial boundary accepts typed containment denial`() {
        assertFutureDenied { throw ExecutionException(AccessDeniedException("forbidden")) }
    }

    private fun assertFutureDenied(operation: () -> Any?) {
        val result = try {
            Result.success(operation())
        } catch (failure: ExecutionException) {
            Result.failure(failure)
        } catch (failure: InterruptedException) {
            Result.failure(failure)
        }
        val failure = result.exceptionOrNull()
        assertNotNull(failure, "Should have failed")
        promoteFatalOrInterruption(failure)
        val cause = if (failure is ExecutionException) failure.cause else failure
        if (!hasOperationPermissionEvidence(cause, allowLaunch = true)) {
            throw failure
        }
    }

    private fun hasOperationPermissionEvidence(
        failure: Throwable?,
        allowLaunch: Boolean,
    ): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var primary = failure
        // Only containment translation wrappers may expose their original operation failure.
        // Suppressed failures and unrelated wrapper types never authorize a denial.
        while (primary is ContainmentViolationException) {
            if (!seen.add(primary)) return false
            val errno = primary.errno
            if (errno != null) return errno == 1 || errno == 13
            primary = primary.cause
        }
        if (primary is AccessDeniedException) return true
        if (primary is java.nio.file.FileSystemException) {
            return primary.reason in listOf("Permission denied", "Operation not permitted")
        }
        if (!allowLaunch || primary !is java.io.IOException) return false
        val receipt = primary.cause as? java.io.IOException ?: return false
        val receiptText = receipt.message ?: return false
        if (receipt.cause != null ||
            !Regex("Exec failed, error: (1 \\(Operation not permitted\\)|13 \\(Permission denied\\))").matches(receiptText.trimEnd(' ', '\t'))
        ) {
            return false
        }
        val launchText = primary.message ?: return false
        return Regex(
            "Cannot run program \"[^\\r\\n]*\"(?: \\(in directory \"[^\\r\\n]*\"\\))?: " + Regex.escape(receiptText),
        ).matches(launchText)
    }

    private fun promoteFatalOrInterruption(failure: Throwable) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Throwable>()
        val failures = mutableListOf<Throwable>()
        pending.add(failure)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            failures.add(current)
            current.cause?.let { pending.add(it) }
            current.suppressed.forEach { pending.add(it) }
        }
        failures.filterIsInstance<Error>().firstOrNull()?.let { throw it }
        failures.filterIsInstance<InterruptedException>().firstOrNull()?.let {
            Thread.currentThread().interrupt()
            throw it
        }
    }

    @Test
    fun `F04 Future boundary accepts permission-specific structured denial`() {
        for (errno in listOf(1, 13)) {
            assertFutureDenied { throw ExecutionException(ContainmentViolationException("permission failure", errno = errno)) }
        }
    }

    @Test
    fun `F04 modeled classification accepts concrete translated operation denials`() {
        for (operationFailure in listOf(
            AccessDeniedException("modeled-path"),
            java.nio.file.FileSystemException("modeled-path", null, "Permission denied"),
        )) {
            val translated = ContainmentViolationException("translated operation", operationFailure)
            assertFutureDenied { throw ExecutionException(translated) }
            assertForbiddenReadDenied { throw translated }
        }
        val receipt = java.io.IOException("Exec failed, error: 13 (Permission denied) ")
        val launch = java.io.IOException("Cannot run program \"/bin/echo\": ${receipt.message}", receipt)
        assertFutureDenied { throw ExecutionException(ContainmentViolationException("translated launch", launch)) }
    }

    @Test
    fun `F04 modeled classification rejects filename programname and business-message spoofs`() {
        val receipt = java.io.IOException("Exec failed, error: 22 (Invalid argument)")
        val failures = listOf(
            java.nio.file.FileSystemException("Permission denied", null, "Invalid argument"),
            java.io.IOException("Permission denied"),
            java.io.IOException("Cannot run program \"Permission denied\": ${receipt.message}", receipt),
            java.io.IOException("Cannot run program \"Exec failed, error: 13 (Permission denied)\""),
            IllegalStateException("unrelated primary", AccessDeniedException("modeled-path")),
        )
        for (operationFailure in failures) {
            val translated = ContainmentViolationException("translated unknown failure", operationFailure)
            val transport = ExecutionException(translated)
            assertSame(transport, assertFailsWith<ExecutionException> { assertFutureDenied { throw transport } })
            assertSame(translated, assertFailsWith<ContainmentViolationException> { assertForbiddenReadDenied { throw translated } })
        }
        val explicitInvalid = ContainmentViolationException("Permission denied", AccessDeniedException("modeled-path"), errno = 22)
        val transport = ExecutionException(explicitInvalid)
        assertSame(transport, assertFailsWith<ExecutionException> { assertFutureDenied { throw transport } })
        assertSame(explicitInvalid, assertFailsWith<ContainmentViolationException> { assertForbiddenReadDenied { throw explicitInvalid } })
    }

    @Test
    fun `F04 Future boundary rejects nonpermission and unknown structured failures`() {
        for (errno in listOf(22, null)) {
            val failure = ExecutionException(ContainmentViolationException("structured failure", errno = errno))
            assertSame(failure, assertFailsWith<ExecutionException> { assertFutureDenied { throw failure } })
        }
    }

    @Test
    fun `F04 Future boundary rejects unrelated primary with suppressed denial`() {
        val primary = ContainmentViolationException("unrelated primary", errno = 22)
        primary.addSuppressed(AccessDeniedException("suppressed denial"))
        val failure = ExecutionException(primary)
        assertSame(failure, assertFailsWith<ExecutionException> { assertFutureDenied { throw failure } })
    }

    @Test
    fun `F04 Future boundary preserves null cause and unrelated primary identity`() {
        val unrelated = IllegalStateException("unrelated primary")
        unrelated.addSuppressed(AccessDeniedException("suppressed denial"))
        for (failure in listOf(ExecutionException(null as Throwable?), ExecutionException(unrelated))) {
            assertSame(failure, assertFailsWith<ExecutionException> { assertFutureDenied { throw failure } })
        }
    }

    @Test
    fun `F04 Future boundary promotes fatal identity across denial causes and suppressed cycles`() {
        for (suppressed in listOf(false, true)) {
            val fatal = LinkageError("nested fatal")
            val denial = AccessDeniedException("denial")
            if (suppressed) denial.addSuppressed(fatal) else denial.initCause(fatal)
            fatal.addSuppressed(denial)
            assertSame(fatal, assertFailsWith<LinkageError> { assertFutureDenied { throw ExecutionException(denial) } })
        }
    }

    @Test
    fun `F04 Future boundary promotes interruption identity across denial causes and suppressed cycles`() {
        for (suppressed in listOf(false, true)) {
            val interrupted = InterruptedException("nested interruption")
            val denial = AccessDeniedException("denial")
            if (suppressed) denial.addSuppressed(interrupted) else denial.initCause(interrupted)
            interrupted.addSuppressed(denial)
            try {
                assertSame(interrupted, assertFailsWith<InterruptedException> { assertFutureDenied { throw ExecutionException(denial) } })
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `F04 Future boundary gives fatal precedence over interruption and denial`() {
        val fatal = LinkageError("fatal alongside interruption")
        val interrupted = InterruptedException("interruption alongside fatal")
        val denial = AccessDeniedException("denial")
        denial.initCause(interrupted)
        denial.addSuppressed(fatal)
        fatal.addSuppressed(denial)
        assertSame(fatal, assertFailsWith<LinkageError> { assertFutureDenied { throw ExecutionException(denial) } })
    }

    @Test
    fun `F04 read boundary promotes fatal identity across denial causes and suppressed cycles`() {
        for (suppressed in listOf(false, true)) {
            val fatal = LinkageError("read fatal")
            val denial = AccessDeniedException("read denial")
            if (suppressed) denial.addSuppressed(fatal) else denial.initCause(fatal)
            fatal.addSuppressed(denial)
            assertSame(fatal, assertFailsWith<LinkageError> { assertForbiddenReadDenied { throw denial } })
        }
    }

    @Test
    fun `F04 read boundary gives fatal precedence over interruption and denial`() {
        val fatal = LinkageError("fatal read alongside interruption")
        val interrupted = InterruptedException("read interruption")
        val denial = AccessDeniedException("read denial")
        denial.initCause(interrupted)
        denial.addSuppressed(fatal)
        fatal.addSuppressed(denial)
        assertSame(fatal, assertFailsWith<LinkageError> { assertForbiddenReadDenied { throw denial } })
    }

    @Test
    fun `F04 read boundary promotes interruption identity across denial causes and suppressed cycles`() {
        for (suppressed in listOf(false, true)) {
            val interrupted = InterruptedException("nested read interruption")
            val denial = AccessDeniedException("read denial")
            if (suppressed) denial.addSuppressed(interrupted) else denial.initCause(interrupted)
            interrupted.addSuppressed(denial)
            try {
                assertSame(interrupted, assertFailsWith<InterruptedException> { assertForbiddenReadDenied { throw denial } })
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `F04 read boundary restores cleared interruption on physical worker`() {
        // Share the existing 120-second test budget; reserve its final 10 seconds for retirement.
        val deadline = System.nanoTime() +
            java.util.concurrent.TimeUnit.SECONDS
            .toNanos(120)
        val retirementReserve = java.util.concurrent.TimeUnit.SECONDS
            .toNanos(10)
        val task = java.util.concurrent.FutureTask {
            val physicalWorker = Thread.currentThread()
            assertTrue(!physicalWorker.isVirtual)
            var original: InterruptedException? = null
            val observed = assertFailsWith<InterruptedException> {
                assertForbiddenReadDenied {
                    physicalWorker.interrupt()
                    try {
                        java.util.concurrent
                            .CountDownLatch(0)
                            .await()
                        error("Interrupted await unexpectedly returned")
                    } catch (failure: InterruptedException) {
                        original = failure
                        assertTrue(!physicalWorker.isInterrupted, "Await must clear the worker flag before the boundary")
                        throw failure
                    }
                }
            }
            assertSame(assertNotNull(original), observed)
            assertTrue(physicalWorker.isInterrupted, "The read boundary must restore the physical worker flag")
            physicalWorker
        }
        val worker = Thread.ofPlatform().unstarted(task)
        worker.start()
        try {
            val outcomeWait = (deadline - System.nanoTime() - retirementReserve).coerceAtLeast(1L)
            assertSame(worker, task.get(outcomeWait, java.util.concurrent.TimeUnit.NANOSECONDS))
        } finally {
            if (worker.isAlive) worker.interrupt()
            var ownerInterrupted = Thread.interrupted()
            try {
                while (worker.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    try {
                        worker.join(java.time.Duration.ofNanos(remaining))
                    } catch (_: InterruptedException) {
                        ownerInterrupted = true
                    }
                }
                assertTrue(!worker.isAlive, "Physical-worker retirement gap: acquired worker remains alive at the test deadline")
            } finally {
                if (ownerInterrupted) Thread.currentThread().interrupt()
            }
        }
        assertTrue(!worker.isAlive, "The acquired physical worker must have retired")
    }

    private fun assertForbiddenReadDenied(read: () -> String) {
        val result = runCatching { read() }
        val failure = result.exceptionOrNull()
        assertNotNull(failure, "Forbidden read unexpectedly succeeded")
        promoteFatalOrInterruption(failure)
        if (!hasOperationPermissionEvidence(failure, allowLaunch = false)) throw failure
    }

    @Test
    fun `F04 read boundary rejects structured failure and unrelated primary with suppressed denial`() {
        val unrelated = IllegalStateException("unrelated read primary")
        unrelated.addSuppressed(AccessDeniedException("suppressed read denial"))
        assertSame(unrelated, assertFailsWith<IllegalStateException> { assertForbiddenReadDenied { throw unrelated } })
        for (errno in listOf(22, null)) {
            val failure = ContainmentViolationException("structured read failure", errno = errno)
            failure.addSuppressed(AccessDeniedException("suppressed read denial"))
            assertSame(failure, assertFailsWith<ContainmentViolationException> { assertForbiddenReadDenied { throw failure } })
        }
    }

    @Test
    fun `forbidden read boundary rejects unrestricted success`() {
        val file = Files.createTempFile("landlock-readable-control", ".txt")
        try {
            Files.writeString(file, "secret")
            assertEquals("secret", Files.readString(file))
            assertFailsWith<AssertionError> {
                assertForbiddenReadDenied { Files.readString(file) }
            }
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `forbidden read boundary preserves unrelated failure`() {
        val failure = IllegalStateException("unrelated read failure")
        val observed = assertFailsWith<IllegalStateException> {
            assertForbiddenReadDenied { throw failure }
        }
        assertSame(failure, observed)
    }

    @Test
    fun `forbidden read boundary accepts filesystem denial`() {
        assertForbiddenReadDenied { throw AccessDeniedException("forbidden-control") }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `testLandlockDirectoryRenameResistance`() {
        if (!Landlock.isSupported()) return
        val baseDir = createTempDirectory("landlock_toctou_base")
        val allowedDir = Files.createDirectory(baseDir.resolve("allowed"))
        val forbiddenDir = Files.createDirectory(baseDir.resolve("forbidden"))

        allowedDir.resolve("allowed.txt").writeText("allowed")
        forbiddenDir.resolve("secret.txt").writeText("secret")

        try {
            IsolatedProcessTester.runIsolatedMethod(
                this::class.java.name,
                "testDirectoryRenameResistance",
                baseDir.toString(),
                allowedDir.toString(),
                forbiddenDir.toString(),
            )
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }
}
