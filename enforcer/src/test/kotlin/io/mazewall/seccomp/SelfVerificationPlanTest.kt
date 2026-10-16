package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfVerificationPlanTest {
    private val arch = Arch.AMD64

    @Test
    fun `plans liveness and the policy denied syscall without native execution`() {
        val program =
            BpfFilter.build(
                arch,
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(SeccompAction.ACT_ALLOW)
                    .block(Syscall.CONNECT)
                    .build()
                    .definition,
            )

        val plan = SelfVerificationPlan.create(program.instructions, arch)

        assertEquals(arch.getpid, plan.livenessNr)
        assertTrue(plan.probeLiveness)
        assertTrue(plan.deniedProbes.contains(arch.connect to NativeConstants.EPERM))
    }

    @Test
    fun `merged state drives union-aware denied probes without native execution`() {
        val program = BpfFilter.build(arch, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition)
        val mergedState = ContainerState(
            syscallActions = mapOf(Syscall.CONNECT to SeccompAction.ACT_ERRNO(NativeConstants.EACCES)),
        )

        val plan = SelfVerificationPlan.create(
            instructions = program.instructions,
            arch = arch,
            mergedState = mergedState,
        )

        assertTrue(plan.probeLiveness)
        assertTrue(plan.deniedProbes.contains(arch.connect to NativeConstants.EACCES))
    }
}
