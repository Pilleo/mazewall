package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.*

class PermanentInstallationTest {
    @Test fun `failed attempt never reports another installation as its own progress`() {
        Platform.setProvider(MockPlatformProvider())
        val engine = MockNativeEngine(
            process = MockNativeProcess().apply {
            onPrctl = { command ->
                if (command is io.mazewall.core.PrctlCommand.SetSeccomp) {
                    LinuxNative.SyscallResult.Error(22, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(0L)
                }
            }
        },
        )
        val otherPolicy = Policy
            .builder()
            .block(Syscall.EXECVE)
            .build()
            .definition
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) {
                ContainmentStateRegistry.processState = ContainerState().withNewSeccompPolicy(
                    otherPolicy,
                    mapOf(
                        Syscall.EXECVE to io.mazewall.core.SeccompAction
                        .ACT_ERRNO(),
                    ),
                    io.mazewall.core.SeccompAction.ACT_ALLOW,
                )
                LinuxNative.SyscallResult.Error(22, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(engine)
        val failure = assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(Policy.builder().block(Syscall.EXECVEAT).build())
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
            ContainedExecutors.installOnCurrentThread(Policy.builder().block(Syscall.EXECVE).build())
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
            onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is io.mazewall.core.PrctlCommand.GetSeccomp) 2L else 0L) }
        },
        )
        var installs = 0
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong()) installs++
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(engine)
        val policy = Policy.builder().block(Syscall.EXECVE).build()
        ContainedExecutors.installOnCurrentThread(policy)
        val before = installs
        ContainedExecutors.installOnProcess(policy)
        assertTrue(installs > before, "process policy requires its own TSYNC installation")
        assertEquals(1, ContainmentStateRegistry.processState.filterDepth)
    }

    @Test fun `wrapper failure preserves Landlock on the actual worker`() {
        Platform.setProvider(MockPlatformProvider())
        val engine = MockNativeEngine(
            process = MockNativeProcess().apply {
            onPrctl = { command ->
                if (command is io.mazewall.core.PrctlCommand.SetSeccomp) {
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
        Executors.newSingleThreadExecutor().use { worker ->
            val wrapped = ContainedExecutors.wrap(worker, Policy.builder().allowFsRead("/tmp").build())
            val failure = assertFailsWith<ExecutionException> { wrapped.submit {}.get() }
            val partial = assertIs<io.mazewall.enforcer.api.PartialInstallationException>(failure.cause)
            assertTrue(partial.progress.landlockApplied)
            assertFalse(partial.progress.seccompApplied)
            val state = worker.submit<ContainerState> { ContainmentStateRegistry.threadState }.get()
            assertNotNull(state.landlockPolicy, "irreversible successful Landlock must survive failed Seccomp")
            assertEquals(0, state.filterDepth)
        }
    }
}
