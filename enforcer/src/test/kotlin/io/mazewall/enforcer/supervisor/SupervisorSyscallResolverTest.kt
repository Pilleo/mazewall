package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
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
