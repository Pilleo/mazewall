package io.mazewall.profiler.internal.engine

import io.mazewall.platform.lifecycle.isUnder
import io.mazewall.platform.seccomp.SupervisedKind
import java.nio.file.Path

/** Pure classification of already-resolved path evidence; never publishes or replies. */
internal object ProfilerNoiseClassifier {
    fun isNoise(
        kind: SupervisedKind,
        original: Path,
        bypassPaths: List<Path>,
    ): Boolean {
        if (kind !is SupervisedKind.Open) return false
        // Evidence is already normalized by the resolver. Do not touch arbitrary tracee
        // filesystem paths on the reactor just to suppress observation noise.
        return bypassPaths.any { isUnder(original, it) }
    }
}
