package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.core.Tid
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.testing.withNativeEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import kotlin.test.assertIs

class MemoryReaderTest {
    @Test
    fun `legacy reader cancellation is not classified as denied memory`() {
        val reader = object : ProfilerMemoryReader {
            context(arena: io.mazewall.ffi.memory.NativeArena) override fun readString(
                tid: Tid,
                remoteAddr: Long,
                maxLen: Int,
            ): String? = throw java.util.concurrent.CancellationException("cancel memory read")
        }
        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> {
                with(arena) { reader.readPathString(Tid(7), 100L) }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 13])
    fun `permission failures retain denied inspection evidence`(errno: Int) {
        val engine = object : MockNativeEngine() {
            override val memory = object : io.mazewall.MockNativeMemory() {
                override fun processVmReadv(
                    pid: io.mazewall.core.Pid,
                    localIov: ManagedSegment,
                    liovcnt: Long,
                    remoteIov: ManagedSegment,
                    riovcnt: Long,
                    flags: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(errno, -1L)
            }
        }
        withNativeEngine(engine) {
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val read = with(arena) { RealMemoryReader.readPathString(Tid(1234), 0x1000L) }
                assertIs<PathStringRead.Denied>(read)
            }
        }
    }

    @Test
    fun `default profiler memory reader methods do not recurse`() {
        val reader = object : ProfilerMemoryReader {}

        io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
            val result = with(arena) { reader.readStringFromProcess(Tid(1234), 0L) }

            assertEquals(null, result)
        }
    }

    @Test
    fun `test resolveLink strips deleted suffix`() {
        val tid = Tid(1234)
        val link = "cwd"
        val expectedPath = "/home/user/workspace"
        val mockPath = "$expectedPath (deleted)"

        val mockFs = object : MockNativeFileSystem() {
            override fun readlink(
                path: ManagedSegment,
                buf: ManagedSegment,
                bufsiz: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val bytes = mockPath.toByteArray(StandardCharsets.UTF_8)
                val bufSeg = MemorySegment.ofAddress(buf.address()).reinterpret(buf.byteSize())
                MemorySegment.copy(bytes, 0, bufSeg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
                return LinuxNative.SyscallResult.Success(bytes.size.toLong())
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val fileSystem = mockFs
        }

        withNativeEngine(mockEngine) {
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val reader = RealMemoryReader
                val result = with(arena) { reader.resolveLink(tid, link) }
                assertEquals(expectedPath, result)
            }
        }
    }

    @Test
    fun `test readStringFromProcess throws ContainmentViolationException when null terminator is missing`() {
        val tid = Tid(1234)
        val remoteAddr = 0x1000L
        val mockData = "unterminated string".toByteArray(StandardCharsets.UTF_8)

        val mockMem = object : io.mazewall.MockNativeMemory() {
            override fun processVmReadv(
                pid: io.mazewall.core.Pid,
                localIov: ManagedSegment,
                liovcnt: Long,
                remoteIov: ManagedSegment,
                riovcnt: Long,
                flags: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val localIovSeg = MemorySegment.ofAddress(localIov.address()).reinterpret(localIov.byteSize())
                val localBuf = localIovSeg.get(ValueLayout.ADDRESS, 0).reinterpret(mockData.size.toLong())
                MemorySegment.copy(mockData, 0, localBuf, ValueLayout.JAVA_BYTE, 0L, mockData.size)
                return LinuxNative.SyscallResult.Success(mockData.size.toLong())
            }
        }

        val mockEngine = object : MockNativeEngine() {
            override val memory = mockMem
        }

        withNativeEngine(mockEngine) {
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val reader = RealMemoryReader
                org.junit.jupiter.api.assertThrows<ContainmentViolationException> {
                    with(arena) { reader.readStringFromProcess(tid, remoteAddr, mockData.size) }
                }
                val evidence = assertIs<PathStringRead.Truncated>(with(arena) { reader.readPathString(tid, remoteAddr, mockData.size) })
                assertEquals(mockData.size, evidence.bytesRead)
            }
        }
    }
}
