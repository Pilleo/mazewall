package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BpfNativeCache
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeMemory
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BpfNativeCacheTest {
    private var allocationCount = 0

    @BeforeEach
    fun setUp() {
        allocationCount = 0
        val mockMemory = object : MockNativeMemory() {
            context(arena: NativeArena) override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment {
                allocationCount++
                return super.newSockFProg(filters)
            }
        }
        LinuxNative.setEngine(MockNativeEngine(memory = mockMemory))
        BpfNativeCache.clear()
    }

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        BpfNativeCache.clear()
    }

    @Test
    fun `test cache hit and miss`() {
        val filters1 = listOf(BpfInstruction.Ret(0, 1))
        val filters2 = listOf(BpfInstruction.Ret(0, 2))

        val seg1a = BpfNativeCache.getOrCompute(filters1)
        assertEquals(1, allocationCount)

        val seg1b = BpfNativeCache.getOrCompute(filters1)
        assertEquals(1, allocationCount)
        assertEquals(seg1a.address(), seg1b.address())

        val seg2 = BpfNativeCache.getOrCompute(filters2)
        assertEquals(2, allocationCount)
        assertNotEquals(seg1a.address(), seg2.address())
    }

    @Test
    fun `test clear cache`() {
        val filters = listOf(BpfInstruction.Ret(0, 1))

        BpfNativeCache.getOrCompute(filters)
        assertEquals(1, allocationCount)

        BpfNativeCache.clear()

        BpfNativeCache.getOrCompute(filters)
        assertEquals(2, allocationCount)
    }
}
