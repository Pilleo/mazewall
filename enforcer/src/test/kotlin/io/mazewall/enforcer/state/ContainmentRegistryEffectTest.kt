package io.mazewall.enforcer.state

import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
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
        val policy = Policy
            .builder()
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
        val policy = Policy
            .builder()
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
    fun `rollback restores the supplied thread state without weakening process state`() {
        val processState = ContainerState(filterDepth = 5)
        val expectedThreadState = ContainerState(filterDepth = 2)
        ContainmentStateRegistry.processState = processState
        ContainmentStateRegistry.threadState = ContainerState(filterDepth = 3)

        ContainmentRegistryEffectInterpreter.apply(
            ContainmentRegistryEffect.RestoreThreadState(expectedThreadState),
        )

        assertEquals(processState, ContainmentStateRegistry.processState)
        assertEquals(expectedThreadState, ContainmentStateRegistry.threadState)
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
