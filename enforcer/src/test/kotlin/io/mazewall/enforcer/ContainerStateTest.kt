package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.seccomp.SeccompInstallationState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContainerStateTest {

    @Test
    fun `test withNewSeccompPolicy updates state correctly`() {
        val initialState = ContainerState()
        val policy = Policy.PURE_COMPUTE_UNSAFE.definition
        val nextState = initialState.withNewSeccompPolicy(
            toInstall = policy,
            newBlocks = mapOf(Syscall.EXECVE to SeccompAction.ACT_KILL_PROCESS),
            newDefaultAction = SeccompAction.ACT_KILL_PROCESS
        )

        assertEquals(1, nextState.filterDepth)
        assertEquals(SeccompAction.ACT_KILL_PROCESS, nextState.syscallActions[Syscall.EXECVE])
        assertEquals(SeccompAction.ACT_KILL_PROCESS, nextState.defaultAction)
    }

    @Test
    fun `test withEngineState updates state correctly`() {
        val initialState = ContainerState()
        val nextState = initialState.withEngineState(SeccompInstallationState.Verified)

        assertEquals(SeccompInstallationState.Verified, nextState.engineState)
    }

    @Test
    fun `test withLandlockPolicy updates state correctly`() {
        val initialState = ContainerState()
        val policy = Policy.PURE_COMPUTE.definition
        val nextState = initialState.withLandlockPolicy(policy)

        assertEquals(policy, nextState.landlockPolicy)
    }
}
