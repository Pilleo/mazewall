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
import org.junit.jupiter.api.Test

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
}
