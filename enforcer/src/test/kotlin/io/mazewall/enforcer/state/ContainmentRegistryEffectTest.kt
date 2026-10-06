package io.mazewall.enforcer.state

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import io.mazewall.seccomp.SeccompInstallationState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ContainmentRegistryEffectTest {
    @AfterEach
    fun restoreRegistry() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
    }

    @Test
    fun `thread-scoped seccomp effect updates only the active thread state`() {
        val processState = ContainerState(filterDepth = 4)
        ContainmentStateRegistry.processState = processState
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .block(Syscall.EXECVE)
            .build()
            .definition

        ContainmentRegistryEffectInterpreter.apply(
            ContainmentRegistryEffect.SeccompInstalled(
                processWide = false,
                policy = policy,
                blocks = mapOf(Syscall.EXECVE to SeccompAction.ACT_ERRNO()),
                defaultAction = SeccompAction.ACT_ALLOW,
            ),
        )

        assertEquals(processState, ContainmentStateRegistry.processState)
        assertEquals(1, ContainmentStateRegistry.threadState.filterDepth)
        assertEquals(SeccompAction.ACT_ERRNO(), ContainmentStateRegistry.threadState.syscallActions[Syscall.EXECVE])
    }

    @Test
    fun `process-scoped Landlock effect does not mutate thread-local bookkeeping`() {
        val threadState = ContainerState(filterDepth = 2)
        ContainmentStateRegistry.threadState = threadState
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allowFsRead("/sandbox")
            .build()
            .definition

        ContainmentRegistryEffectInterpreter.apply(
            ContainmentRegistryEffect.LandlockApplied(processWide = true, policy = policy),
        )

        assertEquals(threadState, ContainmentStateRegistry.threadState)
        assertEquals(policy, ContainmentStateRegistry.processState.landlockPolicy)
    }

    @Test
    fun `coordinator publication updates the installing thread rather than the coordinator`() {
        val processState = ContainerState(filterDepth = 5)
        ContainmentStateRegistry.processState = processState
        ContainmentStateRegistry.threadState = ContainerState()
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .block(Syscall.EXECVE)
            .build()
            .definition
        val publish = ContainmentRegistryEffectInterpreter.prepare(
            ContainmentRegistryEffect.SeccompInstalled(false, policy, mapOf(Syscall.EXECVE to SeccompAction.ACT_ERRNO()), SeccompAction.ACT_ALLOW),
        )
        java.util.concurrent.Executors.newSingleThreadExecutor().use { coordinator ->
            coordinator
                .submit {
                publish()
                assertEquals(0, ContainmentStateRegistry.threadState.filterDepth)
            }.get()
        }
        assertEquals(processState, ContainmentStateRegistry.processState)
        assertEquals(1, ContainmentStateRegistry.threadState.filterDepth)
    }

    @Test
    fun `TSYNC engine-state effect records progress for the process and initiating thread`() {
        val state = SeccompInstallationState.SystemCallApplied

        ContainmentRegistryEffectInterpreter.apply(
            ContainmentRegistryEffect.EngineStateUpdated(processWide = true, state = state),
        )

        assertEquals(state, ContainmentStateRegistry.processState.engineState)
        assertEquals(state, ContainmentStateRegistry.threadState.engineState)
    }

    @Test
    fun `terminal failure retains applied state and cannot be replaced by stronger engine evidence`() {
        val failure = IllegalStateException("original installation failure")
        ContainmentStateRegistry.threadState = ContainerState(filterDepth = 2, engineState = SeccompInstallationState.SystemCallApplied)
        ContainmentStateRegistry.processState = ContainerState(filterDepth = 3, engineState = SeccompInstallationState.Verified)
        ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(false, failure))
        ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(false, IllegalStateException("later failure")))
        ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.EngineStateUpdated(false, SeccompInstallationState.Verified))

        val merged = ContainmentStateRegistry.resolveCurrentState()
        assertSame(failure, merged.installationFailure)
        assertEquals(5, merged.filterDepth)
        assertEquals(SeccompInstallationState.Verified, merged.engineState)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `late successful publication cannot erase terminal quarantine`(processWide: Boolean) {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .block(Syscall.EXECVE)
            .build()
            .definition
        val publish = ContainmentRegistryEffectInterpreter.prepare(
            ContainmentRegistryEffect.SeccompInstalled(processWide, policy, mapOf(Syscall.EXECVE to SeccompAction.ACT_ERRNO()), SeccompAction.ACT_ALLOW),
        )
        val failure = IllegalStateException("installation rejected before coordinator publication")
        ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(processWide, failure))

        publish()

        val state = if (processWide) ContainmentStateRegistry.processState else ContainmentStateRegistry.threadState
        assertSame(failure, state.installationFailure)
        assertEquals(1, state.filterDepth)
        assertEquals(SeccompAction.ACT_ERRNO(), state.syscallActions[Syscall.EXECVE])
    }

    @Test
    fun `process failure invalidates merged-state cache and is visible to fresh threads`() {
        ContainmentStateRegistry.resolveCurrentState()
        val failure = IllegalStateException("process baseline failure")
        ContainmentRegistryEffectInterpreter.apply(ContainmentRegistryEffect.InstallationFailed(true, failure))

        assertSame(failure, ContainmentStateRegistry.resolveCurrentState().installationFailure)
        java.util.concurrent.Executors.newSingleThreadExecutor().use { worker ->
            val observed = worker.submit(java.util.concurrent.Callable { ContainmentStateRegistry.resolveCurrentState().installationFailure }).get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertSame(failure, observed)
        }
    }
}
