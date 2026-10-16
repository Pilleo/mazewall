package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PermanentInstallationTest {
    @Test fun `installer preserves Landlock when ruleset close throws after restriction`() {
        assertLandlockCleanupFailure(IllegalStateException("ruleset close failed"))
    }

    @Test fun `installer preserves Landlock and fatal identity when ruleset close throws`() {
        assertLandlockCleanupFailure(AssertionError("fatal ruleset close failed"))
    }

    private fun assertLandlockCleanupFailure(cleanupFailure: Throwable) {
        Platform.setProvider(MockPlatformProvider())
        var restricted = false
        val native = MockNativeEngine(
            fileSystem = MockNativeFileSystem().apply {
            onClose = { fd ->
                if (fd.value == 42 && restricted) throw cleanupFailure
                LinuxNative.SyscallResult.Success(0L)
            }
        },
        ).apply {
            onSyscall = { nr, _, _, _, _, _, _ ->
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_RESTRICT_SELF_NR) restricted = true
                LinuxNative.SyscallResult.Success(42L)
            }
        }
        LinuxNative.setEngine(native)
        val entries = AtomicInteger()
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        Executors.newSingleThreadExecutor().use { worker ->
            val failure = assertFailsWith<ExecutionException> {
                worker
                    .submit(
                    Callable {
                    ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                        receipt.requireInstalled()
                        entries.incrementAndGet()
                    }
                },
                ).get(5, TimeUnit.SECONDS)
            }
            val partial = if (cleanupFailure is Error) {
                assertSame(cleanupFailure, failure.cause)
                assertIs<io.mazewall.enforcer.api.PartialInstallationException>(cleanupFailure.suppressed.single())
            } else {
                assertIs<io.mazewall.enforcer.api.PartialInstallationException>(failure.cause).also {
                    assertSame(cleanupFailure, it.cause)
                }
            }
            assertTrue(restricted)
            assertTrue(partial.progress.landlockApplied)
            assertFalse(partial.progress.seccompApplied)
            assertEquals(0, entries.get())
            val state = worker.submit<ContainerState> { ContainmentStateRegistry.threadState }.get(5, TimeUnit.SECONDS)
            assertNotNull(state.landlockPolicy)
            assertEquals(0, state.filterDepth)
        }
    }

    @Test fun `failed attempt never reports another installation as its own progress`() {
        Platform.setProvider(MockPlatformProvider())
        val engine = MockNativeEngine(
            process = MockNativeProcess().apply {
            onPrctl = { command ->
                if (command is io.mazewall.platform.syscall.PrctlCommand.SetSeccomp) {
                    LinuxNative.SyscallResult.Error(22, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        },
        )
        val otherPolicy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .block(Syscall.EXECVE)
            .build()
            .definition
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) {
                ContainmentStateRegistry.processState = ContainerState().withNewSeccompPolicy(
                    otherPolicy,
                    mapOf(
                        Syscall.EXECVE to io.mazewall.platform.syscall.SeccompAction
                        .ACT_ERRNO(),
                    ),
                    io.mazewall.platform.syscall.SeccompAction.ACT_ALLOW,
                )
                LinuxNative.SyscallResult.Error(22, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(engine)
        val failure = assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.EXECVEAT).build())
        }
        assertFalse(
            failure is io.mazewall.enforcer.api.PartialInstallationException,
            "another installation's published state must not be attributed to this failed attempt",
        )
        assertEquals(0, ContainmentStateRegistry.threadState.filterDepth)
    }

    @Test fun `verification failure retains the successfully installed filter`() {
        Platform.setProvider(MockPlatformProvider())
        val engine = MockNativeEngine()
        engine.onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success(0L) }
        LinuxNative.setEngine(engine)
        val failure = assertFailsWith<io.mazewall.enforcer.api.PartialInstallationException> {
            ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.EXECVE).build())
        }
        assertTrue(failure.progress.seccompApplied)
        assertFalse(failure.progress.landlockApplied)
        assertEquals(1, ContainmentStateRegistry.threadState.filterDepth)
        assertFalse(ContainmentStateRegistry.threadState.isSyscallAllowed(Syscall.EXECVE))
    }

    @AfterEach fun reset() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        PolicyCompilationCache.clear()
    }

    @Test fun `thread policy never satisfies a process installation`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "threadThenProcessBody")
    }

    fun threadThenProcessBody() {
        Platform.setProvider(MockPlatformProvider())
        val engine = MockNativeEngine(
            process = MockNativeProcess().apply {
            onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is io.mazewall.platform.syscall.PrctlCommand.GetSeccomp) 2L else 0L) }
        },
        )
        var installs = 0
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) installs++
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(engine)
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.EXECVE).build()
        ContainedExecutors.installOnCurrentThread(policy)
        val before = installs
        ContainedExecutors.installOnProcess(policy)
        assertTrue(installs > before, "process policy requires its own TSYNC installation")
        assertEquals(1, ContainmentStateRegistry.processState.filterDepth)
    }

    @Test fun `installer failure preserves Landlock on the actual worker`() {
        Platform.setProvider(MockPlatformProvider())
        val engine = MockNativeEngine(
            process = MockNativeProcess().apply {
            onPrctl = { command ->
                if (command is io.mazewall.platform.syscall.PrctlCommand.SetSeccomp) {
                    LinuxNative.SyscallResult.Error(22, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        },
        )
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) {
                LinuxNative.SyscallResult.Error(22, -1)
            } else {
                LinuxNative.SyscallResult.Success(42L)
            }
        }
        LinuxNative.setEngine(engine)
        val entries = AtomicInteger()
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        Executors.newSingleThreadExecutor().use { worker ->
            val failure = assertFailsWith<ExecutionException> {
                worker
                    .submit(
                    Callable {
                    ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                        receipt.requireInstalled()
                        entries.incrementAndGet()
                    }
                },
                ).get(5, TimeUnit.SECONDS)
            }
            val partial = assertIs<io.mazewall.enforcer.api.PartialInstallationException>(failure.cause)
            assertTrue(partial.progress.landlockApplied)
            assertFalse(partial.progress.seccompApplied)
            assertEquals(0, entries.get())
            val state = worker.submit<ContainerState> { ContainmentStateRegistry.threadState }.get(5, TimeUnit.SECONDS)
            assertNotNull(state.landlockPolicy, "irreversible successful Landlock must survive failed Seccomp")
            assertEquals(0, state.filterDepth)
        }
    }
}
