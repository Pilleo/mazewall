package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.MmapFlags
import io.mazewall.platform.lifecycle.MmapProt
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

@NeedsFreshJvm
class TraceeAuthorityMemoryIntegrationTest {
    @Test
    fun `PROT_NONE and unmapped pathname pointers return EFAULT instead of forced reads`() {
        val address = LinuxNative.fileSystem.mmap(0, 4096, MmapProt(0), MmapFlags(0x22), FileDescriptor.ANON, 0).getOrThrow("mmap")
        try {
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                TraceeProcAuthority.capture(LinuxNative.process.gettid().value, LinuxNative).use { authority ->
                    for (pointer in listOf(address, 1L)) {
                        assertEquals(14, assertThrows(TraceeAuthorityFailure::class.java) { authority.readPath(pointer) }.errno)
                    }
                }
            }
            }
        } finally {
            LinuxNative.raw.syscall(Arch.current().munmap.toLong(), NativeArg.LongArg(address), NativeArg.LongArg(4096)).getOrThrow("munmap")
        }
    }

    @Test
    fun `pathname terminator at readable page end does not inspect following PROT_NONE page`() {
        val address = LinuxNative.fileSystem.mmap(0, 8192, MmapProt(3), MmapFlags(0x22), FileDescriptor.ANON, 0).getOrThrow("mmap")
        try {
            val page = MemorySegment.ofAddress(address).reinterpret(8192)
            page.set(ValueLayout.JAVA_BYTE, 4094, 'x'.code.toByte())
            page.set(ValueLayout.JAVA_BYTE, 4095, 0)
            LinuxNative.raw.syscall(Arch.current().mprotect.toLong(), NativeArg.LongArg(address + 4096), NativeArg.LongArg(4096), NativeArg.IntArg(0)).getOrThrow("mprotect")
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                TraceeProcAuthority.capture(LinuxNative.process.gettid().value, LinuxNative).use { authority ->
                    assertEquals("x", authority.readPath(address + 4094))
                }
            }
            }
        } finally {
            LinuxNative.raw.syscall(Arch.current().munmap.toLong(), NativeArg.LongArg(address), NativeArg.LongArg(8192)).getOrThrow("munmap")
        }
    }
}
