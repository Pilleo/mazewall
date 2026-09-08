package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class SyscallNumberResolverTest {
    companion object {
        @JvmStatic
        fun representativeMappings(): Stream<Arguments> =
            Stream.of(
                Arguments.of("filesystem openat", Syscall.OPENAT, Arch.AMD64, 257),
                Arguments.of("filesystem openat", Syscall.OPENAT, Arch.AARCH64, 56),
                Arguments.of("network connect", Syscall.CONNECT, Arch.AMD64, 42),
                Arguments.of("network connect", Syscall.CONNECT, Arch.AARCH64, 203),
                Arguments.of("process clone", Syscall.CLONE, Arch.AMD64, 56),
                Arguments.of("process clone", Syscall.CLONE, Arch.AARCH64, 220),
                Arguments.of("network getsockname", Syscall.GETSOCKNAME, Arch.AMD64, 51),
                Arguments.of("network getpeername", Syscall.GETPEERNAME, Arch.AMD64, 52),
                Arguments.of("unsupported open", Syscall.OPEN, Arch.AARCH64, -1),
                Arguments.of("unsupported fork", Syscall.FORK, Arch.AARCH64, -1),
            )
    }

    @ParameterizedTest(name = "{0} is {3} on {2}")
    @MethodSource("representativeMappings")
    fun `resolves documented architecture numbers`(
        description: String,
        syscall: Syscall,
        arch: Arch,
        expected: Int,
    ) {
        assertEquals(expected, SyscallNumberResolver.numberFor(syscall, arch), description)
        assertEquals(expected, syscall.numberFor(arch), description)
    }
}
