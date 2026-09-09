package io.mazewall.profiler.engine

import io.mazewall.core.isUnder
import io.mazewall.enforcer.supervisor.BypassPaths
import io.mazewall.platform.seccomp.SupervisedKind
import java.nio.file.Path

/** Pure classification of already-resolved path evidence; never publishes or replies. */
internal object ProfilerNoiseClassifier {
    fun isNoise(
        kind: SupervisedKind,
        original: Path,
        resolution: BypassPaths.PathResolution,
        bypassPaths: List<Path>,
    ): Boolean {
        if (kind !is SupervisedKind.Open) return false
        val resolved = when (resolution) {
            is BypassPaths.PathResolution.Resolved -> resolution.path
            is BypassPaths.PathResolution.Missing -> resolution.normalized
            is BypassPaths.PathResolution.Unsafe -> return false
        }
        return bypassPaths.any { isUnder(original, it) || isUnder(resolved, it) }
    }
}
