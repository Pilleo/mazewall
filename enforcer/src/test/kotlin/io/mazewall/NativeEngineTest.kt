package io.mazewall

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeEngineTest {
    @AfterEach
    fun tearDown() {
        // Always reset to real engine after each test to avoid polluting other tests
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `LinuxNative delegates to injected engine`() {
        val mock = MockNativeEngine()
        mock.process.prctlResult = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(42)

        LinuxNative.setEngine(mock)

        val result = LinuxNative.withTransaction { LinuxNative.process.prctl(io.mazewall.core.PrctlCommand.SetNoNewPrivs(true)) }
        assertEquals(42L, result.getOrThrow("test"))
    }

    @Test
    fun `fault injection works for errno`() {
        val mock = MockNativeEngine()
        mock.syscallResult = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(13, -1) // EACCES

        LinuxNative.setEngine(mock)

        val result = LinuxNative.withTransaction { LinuxNative.raw.syscall(1L) }
        assertEquals(13, (result as LinuxNative.SyscallResult.Error).errno)
    }

    @Test
    fun `contracts allow local val initialization inside nativeScope`() {
        val initializedInScope: String
        io.mazewall.ffi.memory.nativeScope {
            initializedInScope = "hello"
        }
        assertEquals("hello", initializedInScope)
    }

    @Test
    fun `SyscallResult isSuccess and isFailure smart casts correctly`() {
        val successResult: LinuxNative.SyscallResult<String, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.SyscallResult.Success("test-value")

        if (successResult.isSuccess()) {
            // Smart cast allows accessing .value directly on successResult
            assertEquals("test-value", successResult.value)
        } else {
            org.junit.jupiter.api.Assertions.fail("Expected success")
        }

        val failureResult: LinuxNative.SyscallResult<String, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.SyscallResult.Error(5, -1L)

        if (failureResult.isFailure()) {
            // Smart cast allows accessing .errno directly on failureResult
            assertEquals(5, failureResult.errno)
        } else {
            org.junit.jupiter.api.Assertions.fail("Expected failure")
        }
    }
}
