package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.NativeEngine
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.OpenFlags
import io.mazewall.core.close
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.getFdOrThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.InterruptedIOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX)
class NativeDescriptorInputStreamCompatibilityTest {
    @Test
    fun `public primary reflection preserves exact schema and default description`() =
        withDescriptor { fd, arena ->
        val stream = NativeDescriptorConstructorContract.construct(mapOf("socketFd" to fd, "arena" to arena))
        Thread.currentThread().interrupt()
        try {
            val failure = assertFailsWith<InterruptedIOException> { stream.read() }
            assertTrue(failure.message.orEmpty().contains("during native descriptor read"))
        } finally {
            Thread.interrupted()
            stream.close()
        }
        assertTrue(fd.isLiveForIo())
        assertTrue(arena.isAlive)
    }

    @Test
    fun `primary captures current memory once before the ambient engine changes`() =
        withDescriptor { fd, arena ->
        LinuxNative.setEngine(MockNativeEngine(memory = markerMemory(fd, 0x41)))
        val stream = NativeDescriptorInputStream(socketFd = fd, arena = arena)
        LinuxNative.setEngine(MockNativeEngine(memory = markerMemory(fd, 0x42)))
        assertEquals(0x41, stream.read())
        val bytes = byteArrayOf(0, 0, 0, 0)
        assertEquals(2, stream.read(bytes, 1, 2))
        assertContentEquals(byteArrayOf(0, 0x41, 0x41, 0), bytes)
        stream.close()
        assertTrue(fd.isLiveForIo())
        assertTrue(arena.isAlive)
    }

    @Test
    fun `additive constructor and subclass retain injected memory across ambient swaps`() =
        withDescriptor { fd, arena ->
        LinuxNative.setEngine(MockNativeEngine(memory = markerMemory(fd, 0x41)))
        val stream = object : NativeDescriptorInputStream(fd, arena, "captured input", markerMemory(fd, 0x43)) {}
        LinuxNative.setEngine(MockNativeEngine(memory = markerMemory(fd, 0x42)))
        assertEquals(0x43, stream.read())
        val bytes = ByteArray(2)
        assertEquals(2, stream.read(bytes))
        assertContentEquals(byteArrayOf(0x43, 0x43), bytes)
        stream.close()
        assertTrue(fd.isLiveForIo())
        assertTrue(arena.isAlive)
    }

    @Test
    fun `original Java constructor and default mask descriptor remain callable`() =
        withDescriptor { fd, arena ->
        val constructor = NativeDescriptorInputStream::class.java.getConstructor(FileDescriptor::class.java, NativeArena::class.java, String::class.java)
        val stream = constructor.newInstance(fd, arena, "Java descriptor")
        assertEquals(-1, stream.read())
        val defaultConstructor = NativeDescriptorInputStream::class.java.getConstructor(
            FileDescriptor::class.java,
            NativeArena::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )
        val defaultStream = defaultConstructor.newInstance(fd, arena, null, 4, null)
        assertEquals(-1, defaultStream.read())
        stream.close()
        defaultStream.close()
        assertTrue(fd.isLiveForIo())
    }

    private fun markerMemory(
        expectedFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        marker: Byte,
    ): MockNativeMemory =
        MockNativeMemory().apply {
            onRead = { fd, buffer, count ->
                assertEquals(expectedFd, fd)
                ManagedSegment.copy(ByteArray(count.toInt()) { marker }, 0, buffer, 0, count.toInt())
                LinuxNative.SyscallResult.Success(count)
            }
        }

    private fun withDescriptor(action: (FileDescriptor<*, FdState.Open, FdOwnership.Owned>, NativeArena) -> Unit) {
        val original = LinuxNative.engineIdentity as NativeEngine
        NativeArena.ofConfined().use { arena ->
            val fd = original.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags(0)).getFdOrThrow("open compatibility fixture")
            try {
                action(fd, arena)
            } finally {
                LinuxNative.setEngine(original)
                fd.close()
            }
        }
    }
}
