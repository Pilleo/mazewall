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
import kotlin.test.assertFalse
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

    @Test
    fun `union plan aligns clone3 ENOSYS, JVM critical allow floor, stacked arg inspections, and arch-unavailable syscalls`() {
        for (targetArch in listOf(Arch.AMD64, Arch.AARCH64)) {
            val deltaPolicy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(SeccompAction.ACT_ALLOW)
                    .block(Syscall.GETPPID)
                    .allowUnsafePrctl()
                    .allowMmapExec()
                    .build()
                    .definition
            val deltaProgram = BpfFilter.build(targetArch, deltaPolicy)
            val criticalNrs = BpfFilter.getJvmCriticalNrs(targetArch)

            val restrictiveMerged =
                ContainerState(
                    filterDepth = 2,
                    defaultAction = SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                    syscallActions =
                        mapOf(
                            Syscall.GETPID to SeccompAction.ACT_ALLOW,
                            Syscall.READ to SeccompAction.ACT_ALLOW,
                            Syscall.GETPPID to SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                            Syscall.ARCH_PRCTL to SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                        ),
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                )

            val fullPlan =
                SelfVerificationPlan.create(
                    instructions = deltaProgram.instructions,
                    arch = targetArch,
                    maxProbes = Int.MAX_VALUE,
                    mergedState = restrictiveMerged,
                )
            assertTrue(fullPlan.probeLiveness)
            assertTrue(
                fullPlan.deniedProbes.contains(targetArch.clone3 to NativeConstants.ENOSYS),
                "Expected clone3 ENOSYS probe on ${targetArch.name}, got ${fullPlan.deniedProbes}",
            )
            assertFalse(
                fullPlan.deniedProbes.any { (nr, errno) -> nr == targetArch.clone3 && errno != NativeConstants.ENOSYS },
                "clone3 must never expect EPERM on ${targetArch.name}",
            )
            assertFalse(
                fullPlan.deniedProbes.any { (nr, _) -> nr in criticalNrs },
                "JVM critical NRs omitted from allow-list must not be probed as denied on ${targetArch.name}",
            )
            assertTrue(
                fullPlan.deniedProbes.none { (nr, _) -> nr < 0 },
                "Arch-unavailable syscall (-1) must never be probed on ${targetArch.name}",
            )
            assertTrue(fullPlan.deniedProbes.contains(Syscall.GETPPID.numberFor(targetArch) to NativeConstants.EPERM))

            val boundedPlan =
                SelfVerificationPlan.create(
                    instructions = deltaProgram.instructions,
                    arch = targetArch,
                    mergedState = restrictiveMerged,
                )
            assertFalse(
                boundedPlan.deniedProbes.any { (nr, errno) -> nr == targetArch.clone3 && errno != NativeConstants.ENOSYS },
            )

            // Stacked prior UnsafePrctlInspector / MmapExecInspector must suppress prctl and mmap even when delta allows them.
            val stackedArgMerged =
                ContainerState(
                    filterDepth = 2,
                    defaultAction = SeccompAction.ACT_ALLOW,
                    syscallActions =
                        mapOf(
                            Syscall.PRCTL to SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                            Syscall.MMAP to SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                            Syscall.GETPPID to SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                        ),
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                )
            val argPlan =
                SelfVerificationPlan.create(
                    instructions = deltaProgram.instructions,
                    arch = targetArch,
                    maxProbes = Int.MAX_VALUE,
                    mergedState = stackedArgMerged,
                )
            assertFalse(argPlan.deniedProbes.any { (nr, _) -> nr == targetArch.prctl })
            assertFalse(argPlan.deniedProbes.any { (nr, _) -> nr == targetArch.mmap })
            assertFalse(argPlan.deniedProbes.any { (nr, _) -> nr in criticalNrs })
            assertTrue(argPlan.deniedProbes.contains(Syscall.GETPPID.numberFor(targetArch) to NativeConstants.EPERM))
        }
    }
}
