package io.mazewall.core

import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertIs

internal class SeccompActionTest {

    companion object {
        @JvmStatic
        fun actionRankings(): Stream<Arguments> = Stream.of(
            Arguments.of(SeccompAction.ACT_KILL_PROCESS, 70, 7),
            Arguments.of(SeccompAction.ACT_KILL_THREAD, 60, 6),
            Arguments.of(SeccompAction.ACT_TRAP, 50, 5),
            Arguments.of(SeccompAction.ACT_ERRNO(), 41, 4),
            Arguments.of(SeccompAction.ACT_TRACE(1), 40, 4),
            Arguments.of(SeccompAction.ACT_NOTIFY, 30, 3),
            Arguments.of(SeccompAction.ACT_LOG, 20, 2),
            Arguments.of(SeccompAction.ACT_ALLOW, 10, 1),
        )

        @JvmStatic
        fun stricterPairs(): Stream<Arguments> = Stream.of(
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

    @ParameterizedTest(name = "Action {0} -> rank {1}, priority {2}")
    @MethodSource("actionRankings")
    fun `verify action rank and priority`(
        action: SeccompAction,
        expectedRank: Int,
        expectedPriority: Int,
    ) {
        assertEquals(expectedRank, action.restrictivenessRank())
        assertEquals(expectedPriority, action.priority)
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
    fun `compile-time exhaustive check on SeccompAction variants`() {
        val actions: List<SeccompAction> = listOf(
            SeccompAction.ACT_KILL_PROCESS,
            SeccompAction.ACT_KILL_THREAD,
            SeccompAction.ACT_TRAP,
            SeccompAction.ACT_ERRNO(),
            SeccompAction.ACT_TRACE(1),
            SeccompAction.ACT_NOTIFY,
            SeccompAction.ACT_LOG,
            SeccompAction.ACT_ALLOW,
        )

        for (action in actions) {
            when (action) {
                is SeccompAction.ACT_KILL_PROCESS -> Unit
                is SeccompAction.ACT_KILL_THREAD -> Unit
                is SeccompAction.ACT_TRAP -> Unit
                is SeccompAction.ACT_ERRNO -> Unit
                is SeccompAction.ACT_TRACE -> Unit
                is SeccompAction.ACT_NOTIFY -> Unit
                is SeccompAction.ACT_LOG -> Unit
                is SeccompAction.ACT_ALLOW -> Unit
            }
        }
    }
}
