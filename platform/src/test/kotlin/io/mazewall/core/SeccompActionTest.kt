package io.mazewall.platform.lifecycle

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal class SeccompActionTest {
    @Test
    fun `invalid action payloads cannot be constructed or copied`() {
        for (errno in listOf(Int.MIN_VALUE, -1, 0, 65_536, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SeccompAction.ACT_ERRNO(errno) }
            assertFailsWith<IllegalArgumentException> { SeccompAction.ACT_ERRNO().copy(errno = errno) }
        }
        for (traceId in listOf(Int.MIN_VALUE, -1, 65_536, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SeccompAction.ACT_TRACE(traceId) }
            assertFailsWith<IllegalArgumentException> { SeccompAction.ACT_TRACE(0).copy(traceId = traceId) }
        }
    }

    @Test
    fun `action encoding preserves full valid payload`() {
        assertEquals(0x00050001, SeccompAction.ACT_ERRNO(1).toKernelReturnCode())
        assertEquals(0x0005ffff, SeccompAction.ACT_ERRNO(65_535).toKernelReturnCode())
        assertEquals(0x7ff00000, SeccompAction.ACT_TRACE(0).toKernelReturnCode())
        assertEquals(0x7ff0ffff, SeccompAction.ACT_TRACE(65_535).toKernelReturnCode())
    }

    companion object {
        @JvmStatic
        fun actionRankings(): Stream<Arguments> =
            Stream.of(
            Arguments.of(
                SeccompAction.ACT_KILL_PROCESS,
                70,
                7,
                NativeConstants.SECCOMP_RET_KILL_PROCESS,
                NativeConstants.SECCOMP_RET_KILL_PROCESS,
            ),
            Arguments.of(
                SeccompAction.ACT_KILL_THREAD,
                60,
                6,
                NativeConstants.SECCOMP_RET_KILL_THREAD,
                NativeConstants.SECCOMP_RET_KILL_THREAD,
            ),
            Arguments.of(SeccompAction.ACT_TRAP, 50, 5, NativeConstants.SECCOMP_RET_TRAP, NativeConstants.SECCOMP_RET_TRAP),
            Arguments.of(
                SeccompAction.ACT_ERRNO(NativeConstants.EACCES),
                41,
                4,
                NativeConstants.SECCOMP_RET_ERRNO,
                NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EACCES,
            ),
            Arguments.of(
                SeccompAction.ACT_TRACE(1),
                40,
                4,
                NativeConstants.SECCOMP_RET_TRACE,
                NativeConstants.SECCOMP_RET_TRACE or 1,
            ),
            Arguments.of(
                SeccompAction.ACT_NOTIFY,
                30,
                3,
                NativeConstants.SECCOMP_RET_USER_NOTIF,
                NativeConstants.SECCOMP_RET_USER_NOTIF,
            ),
            Arguments.of(SeccompAction.ACT_LOG, 20, 2, NativeConstants.SECCOMP_RET_LOG, NativeConstants.SECCOMP_RET_LOG),
            Arguments.of(SeccompAction.ACT_ALLOW, 10, 1, NativeConstants.SECCOMP_RET_ALLOW, NativeConstants.SECCOMP_RET_ALLOW),
        )

        @JvmStatic
        fun stricterPairs(): Stream<Arguments> =
            Stream.of(
            Arguments.of(SeccompAction.ACT_KILL_PROCESS, SeccompAction.ACT_ALLOW, SeccompAction.ACT_KILL_PROCESS),
            Arguments.of(SeccompAction.ACT_ALLOW, SeccompAction.ACT_KILL_PROCESS, SeccompAction.ACT_KILL_PROCESS),
            Arguments.of(SeccompAction.ACT_KILL_PROCESS, SeccompAction.ACT_KILL_THREAD, SeccompAction.ACT_KILL_PROCESS),
            Arguments.of(SeccompAction.ACT_KILL_THREAD, SeccompAction.ACT_TRAP, SeccompAction.ACT_KILL_THREAD),
            Arguments.of(SeccompAction.ACT_TRAP, SeccompAction.ACT_ERRNO(), SeccompAction.ACT_TRAP),
            Arguments.of(SeccompAction.ACT_ERRNO(), SeccompAction.ACT_TRACE(1), SeccompAction.ACT_ERRNO()),
            Arguments.of(SeccompAction.ACT_TRACE(1), SeccompAction.ACT_ERRNO(), SeccompAction.ACT_ERRNO()),
            Arguments.of(SeccompAction.ACT_ERRNO(NativeConstants.EACCES), SeccompAction.ACT_TRACE(1), SeccompAction.ACT_ERRNO(NativeConstants.EACCES)),
            Arguments.of(SeccompAction.ACT_TRACE(1), SeccompAction.ACT_NOTIFY, SeccompAction.ACT_TRACE(1)),
            Arguments.of(SeccompAction.ACT_NOTIFY, SeccompAction.ACT_LOG, SeccompAction.ACT_NOTIFY),
            Arguments.of(SeccompAction.ACT_LOG, SeccompAction.ACT_ALLOW, SeccompAction.ACT_LOG),
            // Tie-breaking: first wins
            Arguments.of(SeccompAction.ACT_ERRNO(NativeConstants.EPERM), SeccompAction.ACT_ERRNO(NativeConstants.EACCES), SeccompAction.ACT_ERRNO(NativeConstants.EPERM)),
            Arguments.of(SeccompAction.ACT_ERRNO(NativeConstants.EACCES), SeccompAction.ACT_ERRNO(NativeConstants.EPERM), SeccompAction.ACT_ERRNO(NativeConstants.EACCES)),
        )
    }

    @Test
    fun `default ACT_ERRNO is a data-class instance`() {
        val deny = SeccompAction.ACT_ERRNO()
        assertIs<SeccompAction.ACT_ERRNO>(deny)
        assertEquals(NativeConstants.EPERM, deny.errno)
    }

    @ParameterizedTest(name = "Action {0} -> rank {1}, priority {2}, kernel return {4}")
    @MethodSource("actionRankings")
    fun `verify action rank priority and kernel encoding`(
        action: SeccompAction,
        expectedRank: Int,
        expectedPriority: Int,
        expectedNativeCode: Int,
        expectedKernelReturnCode: Int,
    ) {
        assertEquals(expectedRank, action.restrictivenessRank())
        assertEquals(expectedPriority, action.priority)
        assertEquals(expectedNativeCode, action.nativeCode)
        assertEquals(expectedKernelReturnCode, action.toKernelReturnCode())
    }

    @ParameterizedTest(name = "stricter({0}, {1}) -> {2}")
    @MethodSource("stricterPairs")
    fun `verify stricter and stricterOf resolution`(
        a: SeccompAction,
        b: SeccompAction,
        expected: SeccompAction,
    ) {
        assertEquals(expected, a.stricter(b))
        assertEquals(expected, SeccompAction.stricterOf(a, b))
    }
}
