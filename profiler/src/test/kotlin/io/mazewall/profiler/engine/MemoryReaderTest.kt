package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeFileSystem
import io.mazewall.core.Tid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MemoryReaderTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `test resolveLink strips deleted suffix`() {
        val mockFs = object : MockNativeFileSystem() {
            context(_: io.mazewall.NativeTransaction)
            override fun readlink(
                path: java.lang.foreign.MemorySegment,
                buf: java.lang.foreign.MemorySegment,
                bufsiz: Long
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val pathStr = "/tmp/my_temp_file (deleted)"
                val bytes = pathStr.toByteArray(Charsets.UTF_8)
                buf.asSlice(0, bytes.size.toLong()).copyFrom(java.lang.foreign.MemorySegment.ofArray(bytes))
                return LinuxNative.SyscallResult.Success(bytes.size.toLong())
            }
        }
        LinuxNative.setEngine(MockNativeEngine(fileSystem = mockFs))

        val resolved = RealMemoryReader.resolveLink(Tid(1234), "fd/3")
        assertEquals("/tmp/my_temp_file", resolved)
    }
}
