package io.mazewall.profiler.compiler

import io.mazewall.BpfFilter
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import io.mazewall.seccomp.BpfSimulator
import io.mazewall.seccomp.SyscallProbeMatrix
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-verdict differential layer for :profiler filters (issue-20260823-172100).
 *
 * Profiling mode rewrites every ACT_ERRNO decision to SECCOMP_RET_USER_NOTIF and force-allows
 * ioctl; the shared platform oracle (BpfSimulator) must predict exactly that, using the same
 * probe matrix as :enforcer's kernel-differential suite.
 */
class ProfilerFilterVerdictMappingTest {

    private val arch = Arch.AMD64

    @Test
    fun `profiling mode maps errno decisions to USER_NOTIF for structural probes`() {
        // NOTE: do not allow READ here — nr==0 is also the structural nr-zero-edge probe.
        val policy = Policy.threadLocalBuilder()
            .defaultAction(SeccompAction.ACT_ERRNO())
            .allow(Syscall.GETPID)
            .build()
        val program = BpfFilter.build(arch, policy.definition, profilingMode = true).instructions

        val excluded = setOf(Syscall.GETPID.numberFor(arch), arch.ioctl)
        for (probe in SyscallProbeMatrix.structural(arch, excluded)) {
            assertEquals(
                NativeConstants.SECCOMP_RET_USER_NOTIF,
                BpfSimulator.simulate(program, probe.nr, arch),
                "Profiling-mode filter must route ${probe.label} to USER_NOTIF",
            )
        }
        // The explicitly allowed syscall is still ALLOW even in profiling mode.
        assertEquals(
            NativeConstants.SECCOMP_RET_ALLOW,
            BpfSimulator.simulate(program, Syscall.GETPID.numberFor(arch), arch),
        )
    }

    @Test
    fun `profiling mode force-allows ioctl for the tracer`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ERRNO())
            .block(Syscall.CONNECT)
            .build()
        val program = BpfFilter.build(arch, policy.definition, profilingMode = true).instructions

        assertEquals(
            NativeConstants.SECCOMP_RET_ALLOW,
            BpfSimulator.simulate(program, arch.ioctl, arch),
            "ioctl must be forced to ALLOW in profiling mode",
        )
        assertEquals(
            NativeConstants.SECCOMP_RET_USER_NOTIF,
            BpfSimulator.simulate(program, Syscall.CONNECT.numberFor(arch), arch),
        )

        // Matched-policy probes are derived from the definition itself.
        val matched = SyscallProbeMatrix.matched(arch, listOf(Syscall.CONNECT.numberFor(arch)))
        assertTrue(matched.isNotEmpty())
        for (probe in matched) {
            assertEquals(
                NativeConstants.SECCOMP_RET_USER_NOTIF,
                BpfSimulator.simulate(program, probe.nr, arch),
                "Matched probe ${probe.label} must be supervised",
            )
        }
    }
}
