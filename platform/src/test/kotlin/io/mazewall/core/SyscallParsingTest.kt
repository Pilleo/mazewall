package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class SyscallParsingTest {
    companion object {
        @JvmStatic
        fun syscallNames(): Stream<Arguments> =
            Stream.of(
                Arguments.of("lowercase", "openat", Syscall.OPENAT),
                Arguments.of("uppercase", "EXECVE", Syscall.EXECVE),
                Arguments.of("mixed case", "cLoNe3", Syscall.CLONE3),
                Arguments.of("underscore", "io_uring_enter", Syscall.IO_URING_ENTER),
                Arguments.of("legacy syscall", "creat", Syscall.CREAT),
                Arguments.of("unknown name", "not_a_syscall", null),
                Arguments.of("empty name", "", null),
            )
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("syscallNames")
    fun `syscall parser is case-insensitive and rejects unknown names`(
        name: String,
        input: String,
        expected: Syscall?,
    ) {
        assertEquals(expected, Syscall.tryParse(input))
    }
}
