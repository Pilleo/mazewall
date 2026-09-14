package io.mazewall.profiler.internal.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.seccomp.SupervisedKind
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProfilerNoiseClassifierTest {
    @Test
    fun `classification uses resolved evidence and path component boundaries without IO`() {
        val roots = listOf(Path.of("/jdk"))
        val noFilesystemAccess = object : Path by Path.of("/jdk/lib/modules") {
            override fun toRealPath(vararg options: java.nio.file.LinkOption): Path = error("Noise classification must not resolve filesystem paths")
        }
        assertTrue(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, noFilesystemAccess, roots))
        assertTrue(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, Path.of("/jdk/missing"), roots))
        assertFalse(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, Path.of("/jdk-evil/lib"), roots))
        assertFalse(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, Path.of("/alias"), roots))
        for (kind in listOf(SupervisedKind.Connect, SupervisedKind.Accept, SupervisedKind.Exec, SupervisedKind.Spawn, SupervisedKind.Unknown)) {
            assertFalse(ProfilerNoiseClassifier.isNoise(kind, Path.of("/jdk/lib/modules"), roots))
        }
    }
}
