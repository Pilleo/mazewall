package io.mazewall.platform.native

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.recordDescriptorAcquisition
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeBpfTest {
    @Test
    fun `descriptor open requires acquisition evidence from the native adapter`() {
        val bpf = object : NativeBpf {
            override fun command(
                cmd: Long,
                attr: io.mazewall.ffi.memory.ManagedSegment,
                size: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Success(70_637L)
        }
        NativeArena.ofConfined().use { arena ->
            val attr = arena.allocate(8)
            assertFailsWith<IllegalStateException> {
                bpf.openDescriptor(BpfDescriptorCommand.MAP_CREATE, attr, 8, "open BPF descriptor")
            }
        }
    }

    @Test
    fun `openDescriptor adopts a bpf fd from the architecture syscall number`() {
        var observedNr = 0L
        var observedCmd = 0L
        val result = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(70_638L)
        result.recordDescriptorAcquisition()
        val originalReceipt = result.descriptorReceipts.single()
        val raw = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, method, args ->
            check(method.name == "syscall")
            observedNr = args[0] as Long
            observedCmd = (args[1] as NativeArg.LongArg).value
            result
        } as RawSyscallOperations
        NativeArena.ofConfined().use { arena ->
            val attr = arena.allocate(8)
            val fd = raw.asBpf().openDescriptor(BpfDescriptorCommand.MAP_CREATE, attr, 8, "bpf(MAP_CREATE)")
            try {
                assertEquals(FileDescriptorRole.Bpf, fd.role)
                assertEquals(70_638, fd.value)
                assertEquals(Syscall.BPF.numberFor(Arch.current()).toLong(), observedNr)
                assertEquals(0L, observedCmd)
                assertTrue(fd.isLiveForIo())
                assertSame(originalReceipt, result.descriptorReceipts.single())
                assertFailsWith<IllegalStateException> { result.getFdOrThrow("second acquisition") }
            } finally {
                assertTrue(fd.claimForClose())
            }
        }
    }

    @Test
    fun `map lookup is not an fd-returning bpf command`() {
        assertEquals(BpfDescriptorCommand.MAP_CREATE, BpfDescriptorCommand.fromCode(0))
        assertNull(BpfDescriptorCommand.fromCode(1)) // BPF_MAP_LOOKUP_ELEM
        assertNull(BpfDescriptorCommand.fromCode(Long.MAX_VALUE))
        assertTrue(isBpfFdReturningCommand(0))
        assertFalse(isBpfFdReturningCommand(1))
    }

    @Test
    fun `descriptor open preserves native errno`() {
        val raw = MockNativeEngine().apply {
            syscallResult = LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
        }
        NativeArena.ofConfined().use { arena ->
            val failure = assertFailsWith<IllegalStateException> {
                raw.asBpf().openDescriptor(BpfDescriptorCommand.MAP_CREATE, arena.allocate(8), 8, "bpf(MAP_CREATE)")
            }
            assertEquals("bpf(MAP_CREATE) failed with errno=1 (EPERM) (raw return=-1)", failure.message)
        }
    }
}
