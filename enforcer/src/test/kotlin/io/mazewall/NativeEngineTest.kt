package io.mazewall

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeEngineTest {
    @AfterEach
    fun tearDown() {
        // Always reset to real engine after each test to avoid polluting other tests
        LinuxNative.setEngine(RealNativeEngine)
    }

    @Test
    fun `LinuxNative delegates to injected engine`() {
        val mock = MockNativeEngine()
        mock.process.prctlResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(42)

        LinuxNative.setEngine(mock)

        val result = LinuxNative.withTransaction { LinuxNative.process.prctl(0) }
        assertEquals(42L, result.getOrThrow("test"))
    }

    @Test
    fun `fault injection works for errno`() {
        val mock = MockNativeEngine()
        mock.syscallResult = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(13, -1) // EACCES

        LinuxNative.setEngine(mock)

        val result = LinuxNative.withTransaction { LinuxNative.syscall(1L) }
        assertEquals(13, (result as LinuxNative.SyscallResult.Error).errno)
    }
}
