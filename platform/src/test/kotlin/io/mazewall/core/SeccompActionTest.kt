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
                SeccompAction.ACT_NOTIFY,
                40,
                3,
                NativeConstants.SECCOMP_RET_USER_NOTIF,
                NativeConstants.SECCOMP_RET_USER_NOTIF,
            ),
            Arguments.of(
                SeccompAction.ACT_TRACE(1),
                30,
                4,
                NativeConstants.SECCOMP_RET_TRACE,
                NativeConstants.SECCOMP_RET_TRACE or 1,
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
            Arguments.of(SeccompAction.ACT_ERRNO(), SeccompAction.ACT_NOTIFY, SeccompAction.ACT_ERRNO()),
            Arguments.of(SeccompAction.ACT_NOTIFY, SeccompAction.ACT_ERRNO(), SeccompAction.ACT_ERRNO()),
            Arguments.of(SeccompAction.ACT_ERRNO(), SeccompAction.ACT_TRACE(1), SeccompAction.ACT_ERRNO()),
            Arguments.of(SeccompAction.ACT_TRACE(1), SeccompAction.ACT_ERRNO(), SeccompAction.ACT_ERRNO()),
            Arguments.of(SeccompAction.ACT_ERRNO(NativeConstants.EACCES), SeccompAction.ACT_TRACE(1), SeccompAction.ACT_ERRNO(NativeConstants.EACCES)),
            Arguments.of(SeccompAction.ACT_NOTIFY, SeccompAction.ACT_TRACE(1), SeccompAction.ACT_NOTIFY),
            Arguments.of(SeccompAction.ACT_TRACE(1), SeccompAction.ACT_NOTIFY, SeccompAction.ACT_NOTIFY),
            Arguments.of(SeccompAction.ACT_NOTIFY, SeccompAction.ACT_LOG, SeccompAction.ACT_NOTIFY),
            Arguments.of(SeccompAction.ACT_TRACE(1), SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(1)),
            Arguments.of(SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(1), SeccompAction.ACT_TRACE(1)),
            Arguments.of(SeccompAction.ACT_LOG, SeccompAction.ACT_ALLOW, SeccompAction.ACT_LOG),
            // Tie-breaking: first wins
            Arguments.of(SeccompAction.ACT_ERRNO(NativeConstants.EPERM), SeccompAction.ACT_ERRNO(NativeConstants.EACCES), SeccompAction.ACT_ERRNO(NativeConstants.EPERM)),
            Arguments.of(SeccompAction.ACT_ERRNO(NativeConstants.EACCES), SeccompAction.ACT_ERRNO(NativeConstants.EPERM), SeccompAction.ACT_ERRNO(NativeConstants.EACCES)),
            Arguments.of(SeccompAction.ACT_TRACE(1), SeccompAction.ACT_TRACE(2), SeccompAction.ACT_TRACE(1)),
            Arguments.of(SeccompAction.ACT_TRACE(2), SeccompAction.ACT_TRACE(1), SeccompAction.ACT_TRACE(2)),
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

    @Test
    fun `restrictivenessRank and stricter match signed Linux UAPI action order for every pair`() {
        val orderedUapi =
            listOf(
                SeccompAction.ACT_KILL_PROCESS to 0x80000000.toInt(),
                SeccompAction.ACT_KILL_THREAD to 0x00000000,
                SeccompAction.ACT_TRAP to 0x00030000,
                SeccompAction.ACT_ERRNO(NativeConstants.EPERM) to 0x00050000,
                SeccompAction.ACT_NOTIFY to 0x7fc00000,
                SeccompAction.ACT_TRACE(7) to 0x7ff00000,
                SeccompAction.ACT_LOG to 0x7ffc0000,
                SeccompAction.ACT_ALLOW to 0x7fff0000,
            )

        // Linux orders actions by the signed 32-bit masked return word; unsigned puts KILL_PROCESS last.
        assertTrue(SeccompAction.ACT_KILL_PROCESS.nativeCode < 0)
        assertTrue(SeccompAction.ACT_KILL_PROCESS.nativeCode.toUInt() > SeccompAction.ACT_ALLOW.nativeCode.toUInt())

        for (index in orderedUapi.indices) {
            val (action, expectedMask) = orderedUapi[index]
            assertEquals(expectedMask, action.nativeCode, "Unexpected nativeCode for $action")
            if (index > 0) {
                val (stricterAction, stricterMask) = orderedUapi[index - 1]
                assertTrue(stricterMask < expectedMask, "Expected signed UAPI order $stricterAction < $action")
                assertTrue(
                    stricterAction.restrictivenessRank() > action.restrictivenessRank(),
                    "Expected rank($stricterAction) > rank($action)",
                )
            }
        }

        for (i in orderedUapi.indices) {
            val a = orderedUapi[i].first
            assertEquals(a, a.stricter(a))
            assertEquals(a, SeccompAction.stricterOf(a, a))
            for (j in (i + 1) until orderedUapi.size) {
                val b = orderedUapi[j].first
                assertEquals(a, a.stricter(b), "Expected $a.stricter($b) == $a")
                assertEquals(a, b.stricter(a), "Expected $b.stricter($a) == $a")
                assertEquals(a, SeccompAction.stricterOf(a, b), "Expected stricterOf($a, $b) == $a")
                assertEquals(a, SeccompAction.stricterOf(b, a), "Expected stricterOf($b, $a) == $a")
            }
        }

        // First-policy tie-breaking on payload-bearing actions.
        val eperm = SeccompAction.ACT_ERRNO(NativeConstants.EPERM)
        val eacces = SeccompAction.ACT_ERRNO(NativeConstants.EACCES)
        assertEquals(eperm, eperm.stricter(eacces))
        assertEquals(eacces, eacces.stricter(eperm))

        val trace1 = SeccompAction.ACT_TRACE(1)
        val trace2 = SeccompAction.ACT_TRACE(2)
        assertEquals(trace1, trace1.stricter(trace2))
        assertEquals(trace2, trace2.stricter(trace1))
    }
}
