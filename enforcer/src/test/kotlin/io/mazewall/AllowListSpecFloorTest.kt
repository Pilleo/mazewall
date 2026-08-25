package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.engine.JvmFloorPresets
import io.mazewall.ffi.NativeConstants
import io.mazewall.seccomp.BpfSimulator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for issue-20260823-190000 follow-up: ALLOW_LIST specs must seed the
 * canonical JVM floor by construction. A bare EPERM-default allow-list without the floor
 * corrupts lazy bootstrap classloads (missing PREAD64) and deadlocks coordination.
 */
class AllowListSpecFloorTest {

    private val arch = Arch.AMD64

    @Test
    fun `allowList seeds the full JVM floor without operator effort`() {
        val policy = PolicyLists.allowList {
            allow(Syscall.CONNECT) // operator asks for exactly one thing
        }

        val program = BpfFilter.build(arch, policy.definition).instructions

        // Every floor member is allowed despite not being named by the operator.
        // EXCEPTION: prctl keeps its argument inspection — unsafe options are still denied
        // even though prctl itself is a floor member.
        for (sys in JvmFloorPresets.fullJvmFloor()) {
            val nr = sys.numberFor(arch)
            if (nr < 0) continue
            val expected = if (sys == Syscall.PRCTL) {
                NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM
            } else {
                NativeConstants.SECCOMP_RET_ALLOW
            }
            assertEquals(
                expected,
                BpfSimulator.simulate(program, nr, arch),
                "floor member ${sys.name} verdict mismatch",
            )
        }
    }

    @Test
    fun `operator allows still hold and unlisted syscalls stay denied`() {
        val policy = PolicyLists.allowList {
            allow(Syscall.CONNECT)
        }

        val program = BpfFilter.build(arch, policy.definition).instructions
        assertEquals(
            NativeConstants.SECCOMP_RET_ALLOW,
            BpfSimulator.simulate(program, Syscall.CONNECT.numberFor(arch), arch),
        )
        assertEquals(
            NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM,
            BpfSimulator.simulate(program, Syscall.SOCKET.numberFor(arch), arch),
            "unlisted syscall must remain denied",
        )
    }

    @Test
    fun `pread64 is part of the seeded floor`() {
        assertTrue(JvmFloorPresets.fullJvmFloor().any { it == Syscall.PREAD64 })
    }
}
