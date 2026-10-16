package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class BpfOverflowFixTest {
    @Test
    fun `linear scan filter generation handles 100 syscalls without jump offset overflow`() {
        val arch = Arch.AMD64
        val blocked = IntArray(100) { it + 1000 } // Use numbers that won't clash with preamble

        val actions = blocked.associateWith {
            io.mazewall.platform.syscall.SeccompAction
            .ACT_ERRNO()
        }
        val program = BpfFilter.buildFromActions(arch, actions, io.mazewall.platform.syscall.SeccompAction.ACT_ALLOW, DefaultSyscallInspectionPipeline(emptyList()))
        val filters = program.instructions

        assertTrue(filters.isNotEmpty())
        println("Filter with 100 syscalls: ${filters.size} instructions")

        // Verify linear scan properties:
        // A linear scan is robust against jump offset limit overflows because
        // each check is localized with a small relative jump offset.
        // The fact that it didn't throw IllegalStateException (offset > 255) is the key.
    }

    @Test
    fun `PURE_COMPUTE_UNSAFE policy builds successfully on current arch`() {
        val arch = Arch.current()
        val program = BpfFilter.build(arch, Policy.PURE_COMPUTE_UNSAFE.definition)
        val filters = program.instructions
        assertTrue(filters.isNotEmpty())
        println("PURE_COMPUTE_UNSAFE filter size: ${filters.size} instructions")
    }
}
