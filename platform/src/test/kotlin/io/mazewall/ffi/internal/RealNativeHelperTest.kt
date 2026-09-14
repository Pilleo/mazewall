package io.mazewall.ffi.internal

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.CloneFlags
import io.mazewall.platform.lifecycle.MemoryAddress
import io.mazewall.platform.lifecycle.MmapFlags
import io.mazewall.platform.lifecycle.MmapProt
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.lifecycle.Uid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
        assertEquals(99L, RealNativeHelper.toLong(FileDescriptor.generic(99)))
    }
}
