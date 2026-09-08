package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SupervisorSyscallResolverTest {
    @Test
    fun `unknown syscall number is not resolved as open`() {
        val resolution = SupervisorSyscallResolver.resolve(Int.MAX_VALUE, Arch.AMD64)

        assertIs<SupervisorSyscallResolution.Unknown>(resolution)
    }

    @Test
    fun `known architecture syscall retains its semantic identity`() {
        val resolution = SupervisorSyscallResolver.resolve(Syscall.OPENAT.numberFor(Arch.AMD64), Arch.AMD64)

        assertEquals(Syscall.OPENAT, assertIs<SupervisorSyscallResolution.Known>(resolution).syscall)
    }
}
