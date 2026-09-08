package io.mazewall.seccomp

import io.mazewall.BpfFilter
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.ffi.NativeConstants
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
                Policy
                    .builder()
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
        val program = BpfFilter.build(arch, Policy.builder().build().definition)
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
