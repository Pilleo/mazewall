package io.mazewall.profiler

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.networking.NativeDescriptorInputStream
import io.mazewall.profiler.internal.NativeSocketInputStream
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProfilerRuntimeTest {
    @Test
    fun `readers with identical descriptors use their injected runtime concurrently`() {
        val barrier = CyclicBarrier(2)
        val readers = Executors.newFixedThreadPool(2)

        fun read(marker: Byte) =
            readers.submit(
            Callable {
            val memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    barrier.await()
                    buf.writeByte(0, marker)
                    return LinuxNative.SyscallResult.Success(1)
                }
            }
            NativeArena.ofConfined().use { arena ->
                NativeSocketInputStream(FileDescriptor.replace<FileDescriptorRole.Generic>(603), arena, memory).read()
            }
        },
        )
        try {
            val first = read(11)
            val second = read(29)
            assertEquals(11, first.get())
            assertEquals(29, second.get())
        } finally {
            readers.shutdownNow()
        }
    }

    @Test
    fun `native reader retains its original constructor descriptor`() {
        assertNotNull(
            NativeDescriptorInputStream::class.java.getConstructor(
            FileDescriptor::class.java,
            NativeArena::class.java,
            String::class.java,
        ),
        )
    }
}
