package io.mazewall.platform.native

import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeMappingTest {
    @Test
    fun `close retries munmap after a failed unmap`() {
        val unmapped = mutableListOf<Long>()
        var failOnce = true
        val native = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, method, args ->
            check(method.name == "syscall")
            when (args[0] as Long) {
                Syscall.MMAP.numberFor(Arch.current()).toLong() ->
                    SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0x1000L)
                Syscall.MUNMAP.numberFor(Arch.current()).toLong() -> {
                    val address = (args[1] as NativeArg.LongArg).value
                    unmapped += address
                    if (failOnce) {
                        failOnce = false
                        SyscallResult.Error<SyscallHandledState.Unhandled>(22, -1L)
                    } else {
                        SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
                    }
                }
                else -> error("unexpected syscall ${args[0]}")
            }
        } as RawSyscallOperations

        val mapping = NativeMapping.map(native, 7, 4096, 1, 0)
        assertFailsWith<IllegalStateException> { mapping.close() }
        mapping.close()
        assertEquals(listOf(0x1000L, 0x1000L), unmapped)
    }
}
