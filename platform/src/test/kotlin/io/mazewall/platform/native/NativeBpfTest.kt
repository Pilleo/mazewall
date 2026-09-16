package io.mazewall.platform.native

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeBpfTest {
    @Test
    fun `openDescriptor adopts a bpf fd from the architecture syscall number`() {
        var observedNr = 0L
        var observedCmd = 0L
        val raw = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, method, args ->
            check(method.name == "syscall")
            observedNr = args[0] as Long
            observedCmd = (args[1] as NativeArg.LongArg).value
            LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(42L)
        } as RawSyscallOperations
        NativeArena.ofConfined().use { arena ->
            val attr = arena.allocate(8)
            val fd = raw.asBpf().openDescriptor(0, attr, 8, "bpf(MAP_CREATE)")
            assertEquals(FileDescriptorRole.Bpf, fd.role)
            assertEquals(42, fd.value)
            assertEquals(Syscall.BPF.numberFor(Arch.current()).toLong(), observedNr)
            assertEquals(0L, observedCmd)
            assertTrue(fd.isLiveForIo())
        }
    }

    @Test
    fun `map lookup is not an fd-returning bpf command`() {
        assertTrue(isBpfFdReturningCommand(0))
        assertTrue(!isBpfFdReturningCommand(1)) // BPF_MAP_LOOKUP_ELEM
    }
}
