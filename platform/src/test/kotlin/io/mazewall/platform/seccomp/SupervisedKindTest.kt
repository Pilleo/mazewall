package io.mazewall.platform.seccomp

import io.mazewall.core.Arch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupervisedKindTest {

    @Test
    fun `openat is Open on both supported arches`() {
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            assertEquals(SupervisedKind.Open, SupervisedKind.classify(arch.openat, arch))
            assertEquals(SupervisedKind.Exec, SupervisedKind.classify(arch.execve, arch))
            assertEquals(SupervisedKind.Spawn, SupervisedKind.classify(arch.clone, arch))
            assertEquals(SupervisedKind.Unknown, SupervisedKind.classify(999_999, arch))
        }
    }
}
