package io.mazewall.enforcer.api

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CetStatusOperationTest {
    @AfterEach
    fun restoreRuntime() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
    }

    @Test
    fun `enabled-status query uses Linux STATUS operation and never UNLOCK`() {
        org.junit.jupiter.api.Assumptions
            .assumeTrue(Arch.current() == Arch.AMD64, "CET status UAPI is x86_64 specific")
        var unlockCalls = 0
        val process = object : MockNativeProcess() {
            override fun archPrctl(
                code: Int,
                addr: ManagedSegment,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                when (code) {
                    0x5005 -> {
                        addr.writeLong(0, 1L)
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    0x5004 -> {
                        unlockCalls++
                        LinuxNative.SyscallResult.Error(1, -1L)
                    }
                    else -> error("Unexpected CET operation: $code")
                }
        }
        LinuxNative.setEngine(MockNativeEngine(process = process))
        Platform.isCpuCetSupportedOverride = true

        assertEquals(1L, Platform.queryIntelCetStatus())
        assertEquals(0, unlockCalls)
    }
}
