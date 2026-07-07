package io.mazewall.sbob

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Handles normalization, canonicalization, and pruning of filesystem paths.
 */
public object PathNormalizer {
    /**
     * Resolves all paths against [baseCwd] if they are relative, normalizes them,
     * and prunes redundant subpaths (e.g. if /tmp/ is allowed, /tmp/foo is pruned).
     *
     * Pruning is ONLY performed if the parent path is a real directory (not a symbolic link),
     * because Landlock rules with O_NOFOLLOW do not allow recursive access through symlinks.
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
            // Pruning logic:
            // 1. If we don't have a currentParent, or the current path is NOT a subpath of currentParent,
            //    we must include this path in the result.
            // 2. We only update currentParent if the new path is a real directory (not a symlink).
            //    If it's a symlink or a regular file, it cannot serve as a parent for recursive pruning
            //    under Landlock's O_NOFOLLOW semantics.
            if (currentParent == null || !path.startsWith(currentParent)) {
                result.add(path)
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    currentParent = path
                } else {
                    currentParent = null
                }
            }
        }
        return result.map { it.toString() }.toSet()
    }
}
