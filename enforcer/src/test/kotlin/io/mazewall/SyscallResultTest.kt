package io.mazewall

import io.mazewall.LinuxNative.SyscallResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SyscallResultTest {

    @Test
    fun `map should transform success value`() {
        val success: SyscallResult<Long, *> = SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(10L)
        val mapped = success.map { it * 2 }
        
        assertTrue(mapped is SyscallResult.Success)
        assertEquals(20L, (mapped as SyscallResult.Success).value)
    }

    @Test
    fun `map should not transform error value`() {
        val error: SyscallResult<Long, *> = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(1, -1L)
        val mapped = error.map { it * 2 }
        
        assertTrue(mapped is SyscallResult.Error)
        assertEquals(1, (mapped as SyscallResult.Error).errno)
    }

    @Test
    fun `flatMap should chain results`() {
        val success: SyscallResult<Long, *> = SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(10L)
        val chained = success.flatMap { SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(it + 5L) }
        
        assertTrue(chained is SyscallResult.Success)
        assertEquals(15L, (chained as SyscallResult.Success).value)
    }

    @Test
    fun `onSuccess should execute action for success`() {
        var called = false
        val success = SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(10L)
        success.onSuccess {
            called = true
            assertEquals(10L, it)
        }
        assertTrue(called)
    }

    @Test
    fun `onFailure should execute action for error`() {
        var calledErrno = -1
        val error = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(13, -1L)
        error.onFailure { errno, _ ->
            calledErrno = errno
        }
        assertEquals(13, calledErrno)
    }

    @Test
    fun `recover should return success value or transform error`() {
        val success = SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(10L)
        assertEquals(10L, success.recover { _, _ -> 0L })

        val error = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(1, -1L)
        assertEquals(42L, error.recover { _, _ -> 42L })
    }

    @Test
    fun `getOrThrow should unwrap success or throw error`() {
        val success = SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(10L)
        assertEquals(10L, success.getOrThrow("test"))

        val error = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(1, -1L)
        val ex = assertThrows(IllegalStateException::class.java) {
            error.getOrThrow("test")
        }
        assertTrue(ex.message!!.contains("test failed with errno=1 (EPERM)"))
    }

    @Test
    fun `symbolic mapping should resolve common errnos`() {
        val eperm = io.mazewall.core.ErrnoMapping.getSymbolicName(1)
        assertEquals("EPERM", eperm)

        val eacces = io.mazewall.core.ErrnoMapping.getSymbolicName(13)
        assertEquals("EACCES", eacces)

        val einval = io.mazewall.core.ErrnoMapping.getSymbolicName(22)
        assertEquals("EINVAL", einval)

        val unknown = io.mazewall.core.ErrnoMapping.getSymbolicName(9999)
        assertNull(unknown)
    }

    @Test
    fun `SyscallResult Error toString should include symbolic name`() {
        val errorWithSymbol = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(13, -1L)
        assertEquals("Error(errno=13 (EACCES), rawValue=-1)", errorWithSymbol.toString())

        val errorUnknown = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(9999, -1L)
        assertEquals("Error(errno=9999, rawValue=-1)", errorUnknown.toString())
    }

    @Test
    fun `throwErrno should format correctly with unknown errno`() {
        val error = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(9999, -42L)
        val ex = assertThrows(IllegalStateException::class.java) {
            error.throwErrno("action")
        }
        assertEquals("action failed with errno=9999 (raw return=-42)", ex.message)
    }
}
