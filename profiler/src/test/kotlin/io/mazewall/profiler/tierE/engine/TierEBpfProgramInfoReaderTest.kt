package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.ValueLayout
import java.lang.reflect.Proxy

class TierEBpfProgramInfoReaderTest {
    @Test
    fun `native info read retains per-program recursion misses with unavailable run timing`() {
        val observed = TierEBpfProgramInfoReader.read(fakeInfo(returnedLength = 232, missed = 3), 123)
        assertTrue(observed is TierEbpfEngine.BpfProgramExecutionEvidence.IdentityOnly)
        val identity = observed as TierEbpfEngine.BpfProgramExecutionEvidence.IdentityOnly
        assertEquals(321, identity.programId)
        assertEquals(3L, identity.recursionMisses)
        val older = TierEBpfProgramInfoReader.read(fakeInfo(returnedLength = 208, missed = 0), 123)
        assertTrue(older is TierEbpfEngine.BpfProgramExecutionEvidence.IdentityOnly)
        assertEquals(null, (older as TierEbpfEngine.BpfProgramExecutionEvidence.IdentityOnly).recursionMisses)
    }

    @Test
    fun `permission errors during identity query are not converted into unavailable statistics`() {
        val native = fakeInfo(returnedLength = 232, missed = 3, errno = 1)
        val failure = assertThrows(IllegalStateException::class.java) { TierEBpfProgramInfoReader.read(native, 123) }
        assertTrue("EPERM" in failure.message.orEmpty())
    }

    private fun fakeInfo(
        returnedLength: Int,
        missed: Long,
        errno: Int = 0,
    ): RawSyscallOperations =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RawSyscallOperations::class.java)) { _, method, args ->
            check(method.name == "syscall") { "unexpected native operation ${method.name}" }
            if (errno != 0) {
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(errno, -errno.toLong())
            } else {
                val attr = ((requireNotNull(args)[2] as NativeArg.MemoryArg).value as ConfinedSegment).native
                assertEquals(123, attr.get(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.BPF_FD))
                assertEquals(232, attr.get(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.INFO_LEN))
                val info = attr.get(ValueLayout.ADDRESS, BpfObjGetInfoLayout.INFO).reinterpret(BpfProgramInfoLayout.SIZE)
                info.set(ValueLayout.JAVA_INT, BpfProgramInfoLayout.ID, 321)
                info.set(ValueLayout.JAVA_INT, BpfProgramInfoLayout.XLATED_PROGRAM_LENGTH, 456)
                if (returnedLength >= BpfProgramInfoLayout.RECURSION_MISSES + Long.SIZE_BYTES) {
                    info.set(ValueLayout.JAVA_LONG, BpfProgramInfoLayout.RECURSION_MISSES, missed)
                }
                attr.set(ValueLayout.JAVA_INT, BpfObjGetInfoLayout.INFO_LEN, returnedLength)
                LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
            }
        } as RawSyscallOperations
}
