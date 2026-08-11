package io.mazewall.ffi.internal

import io.mazewall.core.CloneFlags
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.MemoryAddress
import io.mazewall.core.MmapFlags
import io.mazewall.core.MmapProt
import io.mazewall.core.OpenFlags
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.core.Uid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RealNativeHelperTest {
    @Test
    fun `toLong converts standard and custom value classes correctly`() {
        assertEquals(10L, RealNativeHelper.toLong(10L))
        assertEquals(10L, RealNativeHelper.toLong(10))
        assertEquals(10L, RealNativeHelper.toLong(10.toShort()))
        assertEquals(10L, RealNativeHelper.toLong(10.toByte()))
        assertEquals(0L, RealNativeHelper.toLong(null))

        assertEquals(123L, RealNativeHelper.toLong(OpenFlags(123)))
        assertEquals(456L, RealNativeHelper.toLong(MmapProt(456)))
        assertEquals(789L, RealNativeHelper.toLong(MmapFlags(789)))
        assertEquals(9999L, RealNativeHelper.toLong(CloneFlags(9999L)))
        assertEquals(12L, RealNativeHelper.toLong(Pid(12)))
        assertEquals(34L, RealNativeHelper.toLong(Tid(34)))
        assertEquals(56L, RealNativeHelper.toLong(Uid(56)))
        assertEquals(1001L, RealNativeHelper.toLong(MemoryAddress(1001L)))
        assertEquals(99L, RealNativeHelper.toLong(FileDescriptor.unsafe<FileDescriptorRole.Generic>(99)))
    }
}
