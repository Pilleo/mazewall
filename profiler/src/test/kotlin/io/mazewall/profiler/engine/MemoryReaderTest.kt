package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.NativeTransaction
import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import io.mazewall.ffi.memory.NativeArena
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
            override fun readlink(path: io.mazewall.ffi.memory.ManagedSegment, buf: io.mazewall.ffi.memory.ManagedSegment, bufsiz: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val bytes = mockPath.toByteArray(StandardCharsets.UTF_8)
                io.mazewall.ffi.memory.ManagedSegment.copy(bytes, 0, buf, 0L, bytes.size)
                return LinuxNative.SyscallResult.Success(bytes.size.toLong())
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val fileSystem = mockFs
        }

        LinuxNative.setEngine(mockEngine)
        try {
            NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RealMemoryReader
                val result = with(arena) { reader.resolveLink(tid, link) }
                assertEquals(expectedPath, result)
            }
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
            override fun processVmReadv(pid: io.mazewall.core.Pid, localIov: io.mazewall.ffi.memory.ManagedSegment, liovcnt: Long, remoteIov: io.mazewall.ffi.memory.ManagedSegment, riovcnt: Long, flags: Long): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val localBuf = localIov.native.get(ValueLayout.ADDRESS, 0).reinterpret(mockData.size.toLong())
                io.mazewall.ffi.memory.ManagedSegment.copy(mockData, 0, io.mazewall.ffi.memory.ConfinedSegment(localBuf), 0L, mockData.size)
                return LinuxNative.SyscallResult.Success(mockData.size.toLong())
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val memory = mockMem
        }

        LinuxNative.setEngine(mockEngine)
        try {
            NativeArena.ofConfined().use { arena ->
            with(arena) {
                val reader = RealMemoryReader
                val result = with(arena) { reader.readStringFromProcess(tid, remoteAddr, mockData.size) }
                assertEquals("unterminated string", result)
            }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }
}
