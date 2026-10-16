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

class TierEInvocationWitnessMapReaderTest {
    @Test
    fun `enumerated live marker ID retains independent raw and main stage counts`() {
        assertEquals(
            mapOf(0x4d5a0000002aL to TierEBenchmarkAuditMapReader.InvocationWitnessCount(1, 0)),
            TierEBenchmarkAuditMapReader.readInvocationWitness(fakeNative(), 17),
        )
    }

    @Test
    fun `native permission failure on token map lookup is terminal`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            TierEBenchmarkAuditMapReader.readInvocationWitness(fakeNative(lookupErrno = 1), 17)
        }
        assertTrue("EPERM" in failure.message.orEmpty())
    }

    private fun fakeNative(lookupErrno: Int = 0): RawSyscallOperations =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RawSyscallOperations::class.java)) { _, method, args ->
            check(method.name == "syscall")
            val arguments = requireNotNull(args)
            val command = (arguments[1] as NativeArg.LongArg).value
            val attr = ((arguments[2] as NativeArg.MemoryArg).value as ConfinedSegment).native
            assertEquals(17, attr.get(ValueLayout.JAVA_INT, 0))
            when (command) {
                4L -> {
                    val key = attr.get(ValueLayout.ADDRESS, 8)
                    if (key.address() == 0L) {
                        attr.get(ValueLayout.ADDRESS, 16).reinterpret(8).set(ValueLayout.JAVA_LONG, 0, 0x4d5a0000002aL)
                        LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
                    } else {
                        LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(2, -2)
                    }
                }
                1L -> if (lookupErrno != 0) {
                    LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(lookupErrno, -lookupErrno.toLong())
                } else {
                    assertEquals(0x4d5a0000002aL, attr.get(ValueLayout.ADDRESS, 8).reinterpret(8).get(ValueLayout.JAVA_LONG, 0))
                    val value = attr.get(ValueLayout.ADDRESS, 16).reinterpret(16)
                    value.set(ValueLayout.JAVA_LONG, 0, 1)
                    value.set(ValueLayout.JAVA_LONG, 8, 0)
                    LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
                }
                else -> error("unexpected BPF command $command")
            }
        } as RawSyscallOperations
}
