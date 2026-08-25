package io.mazewall.platform.seccomp

import io.mazewall.core.Arch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class SupervisedKindTest {

    companion object {
        @JvmStatic
        fun syscallClassifications(): Stream<Arguments> = Stream.of(
            // AMD64 (all legacy and modern syscalls exist)
            Arguments.of(Arch.AMD64.open, Arch.AMD64, SupervisedKind.Open),
            Arguments.of(Arch.AMD64.openat, Arch.AMD64, SupervisedKind.Open),
            Arguments.of(Arch.AMD64.openat2, Arch.AMD64, SupervisedKind.Open),
            Arguments.of(Arch.AMD64.connect, Arch.AMD64, SupervisedKind.Connect),
            Arguments.of(Arch.AMD64.accept, Arch.AMD64, SupervisedKind.Accept),
            Arguments.of(Arch.AMD64.accept4, Arch.AMD64, SupervisedKind.Accept),
            Arguments.of(Arch.AMD64.execve, Arch.AMD64, SupervisedKind.Exec),
            Arguments.of(Arch.AMD64.execveat, Arch.AMD64, SupervisedKind.Exec),
            Arguments.of(Arch.AMD64.fork, Arch.AMD64, SupervisedKind.Spawn),
            Arguments.of(Arch.AMD64.vfork, Arch.AMD64, SupervisedKind.Spawn),
            Arguments.of(Arch.AMD64.clone, Arch.AMD64, SupervisedKind.Spawn),
            Arguments.of(999_999, Arch.AMD64, SupervisedKind.Unknown),
            Arguments.of(-1, Arch.AMD64, SupervisedKind.Unknown),

            // AARCH64 (modern-only: open/fork/vfork are unsupported and mapped to -1)
            Arguments.of(Arch.AARCH64.open, Arch.AARCH64, SupervisedKind.Unknown), // -1
            Arguments.of(Arch.AARCH64.openat, Arch.AARCH64, SupervisedKind.Open),
            Arguments.of(Arch.AARCH64.openat2, Arch.AARCH64, SupervisedKind.Open),
            Arguments.of(Arch.AARCH64.connect, Arch.AARCH64, SupervisedKind.Connect),
            Arguments.of(Arch.AARCH64.accept, Arch.AARCH64, SupervisedKind.Accept),
            Arguments.of(Arch.AARCH64.accept4, Arch.AARCH64, SupervisedKind.Accept),
            Arguments.of(Arch.AARCH64.execve, Arch.AARCH64, SupervisedKind.Exec),
            Arguments.of(Arch.AARCH64.execveat, Arch.AARCH64, SupervisedKind.Exec),
            Arguments.of(Arch.AARCH64.fork, Arch.AARCH64, SupervisedKind.Unknown), // -1
            Arguments.of(Arch.AARCH64.vfork, Arch.AARCH64, SupervisedKind.Unknown), // -1
            Arguments.of(Arch.AARCH64.clone, Arch.AARCH64, SupervisedKind.Spawn),
            Arguments.of(999_999, Arch.AARCH64, SupervisedKind.Unknown),
            Arguments.of(-1, Arch.AARCH64, SupervisedKind.Unknown),
        )
    }

    @ParameterizedTest(name = "Syscall {0} on {1} -> {2}")
    @MethodSource("syscallClassifications")
    fun `verify SupervisedKind classification`(
        nr: Int,
        arch: Arch,
        expectedKind: SupervisedKind,
    ) {
        assertEquals(expectedKind, SupervisedKind.classify(nr, arch))
    }

    @Test
    fun `compile-time exhaustive check on SupervisedKind variants`() {
        val kinds: List<SupervisedKind> = listOf(
            SupervisedKind.Open,
            SupervisedKind.Connect,
            SupervisedKind.Accept,
            SupervisedKind.Exec,
            SupervisedKind.Spawn,
            SupervisedKind.Unknown,
        )

        for (kind in kinds) {
            when (kind) {
                is SupervisedKind.Open -> Unit
                is SupervisedKind.Connect -> Unit
                is SupervisedKind.Accept -> Unit
                is SupervisedKind.Exec -> Unit
                is SupervisedKind.Spawn -> Unit
                is SupervisedKind.Unknown -> Unit
            }
        }
    }
}
