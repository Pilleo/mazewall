package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
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
            newDefaultAction = SeccompAction.ACT_KILL_PROCESS,
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

    @Test
    fun `withNewSeccompPolicy enforces canonical action rank for default and explicit actions`() {
        val dummyPolicy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .allowMmapExec()
                .allowNonThreadClone()
                .allowUnsafePrctl()
                .build()
                .definition

        val tracedState =
            ContainerState(
                filterDepth = 1,
                defaultAction = SeccompAction.ACT_TRACE(7),
                syscallActions = mapOf(Syscall.GETPPID to SeccompAction.ACT_TRACE(7)),
            )

        val notifiedState =
            tracedState.withNewSeccompPolicy(
                toInstall = dummyPolicy,
                newBlocks = mapOf(Syscall.GETPPID to SeccompAction.ACT_NOTIFY),
                newDefaultAction = SeccompAction.ACT_NOTIFY,
            )
        assertEquals(SeccompAction.ACT_NOTIFY, notifiedState.defaultAction)
        assertEquals(SeccompAction.ACT_NOTIFY, notifiedState.syscallActions[Syscall.GETPPID])

        // Attempting a weaker proposal retains ACT_NOTIFY.
        val downgradedFromNotify =
            notifiedState.withNewSeccompPolicy(
                toInstall = dummyPolicy,
                newBlocks = mapOf(Syscall.GETPPID to SeccompAction.ACT_TRACE(7)),
                newDefaultAction = SeccompAction.ACT_TRACE(7),
            )
        assertEquals(SeccompAction.ACT_NOTIFY, downgradedFromNotify.defaultAction)
        assertEquals(SeccompAction.ACT_NOTIFY, downgradedFromNotify.syscallActions[Syscall.GETPPID])

        // ACT_ERRNO escalates over ACT_NOTIFY and rejects downgrade back to ACT_NOTIFY.
        val errno = SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EACCES)
        val errnoState =
            notifiedState.withNewSeccompPolicy(
                toInstall = dummyPolicy,
                newBlocks = mapOf(Syscall.GETPPID to errno),
                newDefaultAction = errno,
            )
        assertEquals(errno, errnoState.defaultAction)
        assertEquals(errno, errnoState.syscallActions[Syscall.GETPPID])

        val downgradedFromErrno =
            errnoState.withNewSeccompPolicy(
                toInstall = dummyPolicy,
                newBlocks = mapOf(Syscall.GETPPID to SeccompAction.ACT_NOTIFY),
                newDefaultAction = SeccompAction.ACT_NOTIFY,
            )
        assertEquals(errno, downgradedFromErrno.defaultAction)
        assertEquals(errno, downgradedFromErrno.syscallActions[Syscall.GETPPID])
    }
}
