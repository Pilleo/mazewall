package io.mazewall.sbob

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Handles normalization, canonicalization, and pruning of filesystem paths.
 */
internal object PathNormalizer {
    /**
     * Resolves all paths against [baseCwd] if they are relative, normalizes them,
     * and prunes redundant subpaths (e.g. if /tmp/ is allowed, /tmp/foo is pruned).
     */
    fun normalizeAndPrune(paths: Set<String>, baseCwd: Path?): Set<String> {
        if (paths.isEmpty()) return paths

        val resolvedPaths = paths.map { pathStr ->
            val p = Paths.get(pathStr)
            if (!p.isAbsolute) {
                if (baseCwd == null) {
                    throw IllegalArgumentException("SBoB contains relative path '$pathStr' but no baseCwd was provided.")
                }
                baseCwd.resolve(p).normalize()
            } else {
                p.normalize()
            }
        }.sorted()

        val result = mutableListOf<Path>()
        var currentParent: Path? = null

        for (path in resolvedPaths) {
            // BACKLOG FIX: Use Path.startsWith(Path) instead of String.startsWith to avoid
            // /etc/hosts matching /etc/hostname as a parent.
            if (currentParent == null || !path.startsWith(currentParent)) {
                result.add(path)
                currentParent = path
            }
        }
        return result.map { it.toString() }.toSet()
    }
}
