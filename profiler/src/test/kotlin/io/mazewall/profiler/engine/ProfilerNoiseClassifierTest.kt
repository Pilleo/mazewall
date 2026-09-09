package io.mazewall.profiler.engine

import io.mazewall.enforcer.supervisor.BypassPaths.PathResolution
import io.mazewall.platform.seccomp.SupervisedKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProfilerNoiseClassifierTest {
    @Test
    fun `classification uses resolved evidence and path component boundaries without IO`() {
        val roots = listOf(Path.of("/jdk"))
        assertTrue(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, Path.of("/alias"), PathResolution.Resolved(Path.of("/jdk/lib/modules")), roots))
        assertTrue(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, Path.of("/jdk/missing"), PathResolution.Missing(Path.of("/jdk/missing")), roots))
        assertFalse(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, Path.of("/jdk-evil/lib"), PathResolution.Resolved(Path.of("/jdk-evil/lib")), roots))
        assertFalse(ProfilerNoiseClassifier.isNoise(SupervisedKind.Open, Path.of("/jdk/lib/modules"), PathResolution.Unsafe("access-denied"), roots))
        for (kind in listOf(SupervisedKind.Connect, SupervisedKind.Accept, SupervisedKind.Exec, SupervisedKind.Spawn, SupervisedKind.Unknown)) {
            assertFalse(ProfilerNoiseClassifier.isNoise(kind, Path.of("/jdk/lib/modules"), PathResolution.Resolved(Path.of("/jdk/lib/modules")), roots))
        }
    }
}
