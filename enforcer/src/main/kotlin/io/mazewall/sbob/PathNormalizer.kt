package io.mazewall.sbob

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Handles normalization, physical resolution (symlink following), and pruning of filesystem paths.
 *
 * This component ensures that paths provided in SBoB files are resolved to their physical locations
 * on disk before being applied as security rules. This prevents Time-of-Check to Time-of-Use (TOCTOU)
 * vulnerabilities and "dot-dot-through-symlink" attacks where a syntactically harmless path
 * (e.g., `/app/link/../etc/passwd`) points to a sensitive location.
 *
 * NOTE ON MULTI-THREADED I/O & TOCTOU: PathNormalizer performs static path-based pruning which
 * inherently assumes a stable filesystem layout during the execution of [normalizeAndPrune].
 * If another thread or process alters directories/symlinks concurrently, the static pruning results
 * could be inconsistent. Once the sandboxing rules are registered under Landlock, however,
 * the policy is secured at the kernel level using inode-based tracking, making runtime accesses
 * immune to directory renaming and symlink swap attacks.
 */
public object PathNormalizer {
    /**
     * Resolves all paths against [baseCwd] if they are relative, resolves them to their physical
     * real path (following symlinks), and prunes redundant subpaths.
     *
     * Pruning is performed to reduce the number of rules added to Landlock. A path is pruned if
     * it is a subpath of a previously included directory.
     *
     * @param paths The set of raw path strings to normalize and prune.
     * @param baseCwd The base directory to resolve relative paths against.
     * @return A set of resolved, absolute, and pruned path strings.
     */
    fun normalizeAndPrune(paths: Set<String>, baseCwd: Path?): Set<String> {
        if (paths.isEmpty()) return paths

        val resolvedPaths = paths.map { pathStr ->
            var p = Paths.get(pathStr)
            if (!p.isAbsolute) {
                if (baseCwd == null) {
                    throw IllegalArgumentException("SBoB contains relative path '$pathStr' but no baseCwd was provided.")
                }
                p = baseCwd.resolve(p)
            }
            val absP = p.toAbsolutePath().normalize()
            val hasSymlink = hasSymbolicLinkComponent(absP)
            val resolved = resolvePhysicalOrSyntactic(p)
            resolved to hasSymlink
        }.groupBy({ it.first }, { it.second })
         .map { (resolved, hasSymlinkList) -> resolved to hasSymlinkList.any { it } }
         .sortedBy { it.first }

        val result = mutableListOf<Path>()
        var currentParent: Path? = null

        for ((path, hasSymlink) in resolvedPaths) {
            // Pruning logic:
            // 1. If we don't have a currentParent, or the current path is NOT a subpath of currentParent,
            //    or if the current path has a symlink component, we must include this path in the result.
            // 2. We only update currentParent if the new path is a real directory (not a symlink) and has no symlink component.
            if (currentParent == null || hasSymlink || !path.startsWith(currentParent)) {
                result.add(path)
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !hasSymlink) {
                    currentParent = path
                } else {
                    currentParent = null
                }
            }
        }
        return result.map { it.toString() }.toSet()
    }

    private fun hasSymbolicLinkComponent(p: Path): Boolean {
        var current: Path? = p
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun resolvePhysicalOrSyntactic(p: Path): Path {
        var current = p.toAbsolutePath()
        var suffix = Paths.get("")
        while (current != null) {
            try {
                // Try to resolve the current parent prefix physically
                val real = current.toRealPath()
                return real.resolve(suffix).normalize()
            } catch (e: IOException) {
                // Parent component doesn't exist or can't be resolved, move up
                val fileName = current.fileName
                if (fileName != null) {
                    suffix = Paths.get(fileName.toString()).resolve(suffix)
                }
                current = current.parent
            } catch (e: SecurityException) {
                val fileName = current.fileName
                if (fileName != null) {
                    suffix = Paths.get(fileName.toString()).resolve(suffix)
                }
                current = current.parent
            }
        }
        return p.toAbsolutePath().normalize()
    }
}
