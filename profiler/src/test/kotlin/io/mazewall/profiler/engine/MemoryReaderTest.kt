package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.NativeTransaction
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

class MemoryReaderTest {

    @Test
    fun `test resolveLink strips deleted suffix`() {
        val tid = Tid(1234)
        val link = "cwd"
        val expectedPath = "/home/user/workspace"
        val mockPath = "$expectedPath (deleted)"

        val mockFs = object : MockNativeFileSystem() {
            context(context: NativeTransaction)
            override fun readlink(path: ManagedSegment, buf: ManagedSegment, bufsiz: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val bytes = mockPath.toByteArray(StandardCharsets.UTF_8)
                java.lang.foreign.MemorySegment.copy(bytes, 0, buf.native, ValueLayout.JAVA_BYTE, 0L, bytes.size)
                return LinuxNative.SyscallResult.Success(bytes.size.toLong())
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val fileSystem = mockFs
        }

        LinuxNative.setEngine(mockEngine)
        try {
            NativeArena.ofConfined().use { arena ->
                val reader = RealMemoryReader
                val result = with(arena) { reader.resolveLink(tid, link) }
                assertEquals(expectedPath, result)
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `test readStringFromProcess returns best-effort string when null terminator is missing`() {
        val tid = Tid(1234)
        val remoteAddr = 0x1000L
        val mockData = "unterminated string".toByteArray(StandardCharsets.UTF_8)

        val mockMem = object : io.mazewall.MockNativeMemory() {
            context(context: NativeTransaction)
            override fun processVmReadv(pid: io.mazewall.core.Pid, localIov: ManagedSegment, liovcnt: Long, remoteIov: ManagedSegment, riovcnt: Long, flags: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val localBuf = localIov.native.get(ValueLayout.ADDRESS, 0).reinterpret(mockData.size.toLong())
                java.lang.foreign.MemorySegment.copy(mockData, 0, localBuf, ValueLayout.JAVA_BYTE, 0L, mockData.size)
                return LinuxNative.SyscallResult.Success(mockData.size.toLong())
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val memory = mockMem
        }

        LinuxNative.setEngine(mockEngine)
        try {
            NativeArena.ofConfined().use { arena ->
                val reader = RealMemoryReader
                val result = with(arena) { reader.readStringFromProcess(tid, remoteAddr, mockData.size) }
                assertEquals("unterminated string", result)
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
