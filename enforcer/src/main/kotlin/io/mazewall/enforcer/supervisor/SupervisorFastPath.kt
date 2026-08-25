package io.mazewall.enforcer.supervisor

import java.nio.file.Path
import java.util.logging.Logger

/**
 * Daemon-side fast-path path resolution (issue-20260823-171956, slice 3).
 *
 * Resolves tracee-supplied paths (absolute, or dirfd-relative via /proc/<pid>/fd|cwd) to real
 * absolute paths for bypass evaluation. No engine state; pure /proc + BypassPaths logic.
 */
internal object SupervisorFastPath {
    private val logger = Logger.getLogger(SupervisorFastPath::class.java.name)

    private const val AT_FDCWD = -100

    /**
     * Best-effort realpath of [pathStr] relative to the tracee's [dirfd]. Returns null when the
     * base /proc handle is gone — callers must fail closed and deny.
     */
    fun resolveAbsolutePath(pid: Int, dirfd: Int, pathStr: String): Path? {
        val path = java.nio.file.Paths.get(pathStr)
        if (path.isAbsolute) {
            try {
                return BypassPaths.toRealPathWithFallback(path)
            } catch (e: java.nio.file.NoSuchFileException) {
                return null
            } catch (e: java.io.FileNotFoundException) {
                return null
            } catch (e: Exception) {
                logger.severe { "Critical error during absolute path resolution for $pathStr: ${e.message}" }
                throw e
            }
        }
        try {
            val baseDir = if (dirfd == AT_FDCWD) {
                BypassPaths.toRealPathWithFallback(java.nio.file.Paths.get("/proc/$pid/cwd"))
            } else {
                BypassPaths.toRealPathWithFallback(java.nio.file.Paths.get("/proc/$pid/fd/$dirfd"))
            }
            return BypassPaths.toRealPathWithFallback(baseDir.resolve(path))
        } catch (e: java.nio.file.NoSuchFileException) {
            // /proc/<pid>/cwd or /proc/<pid>/fd/<dirfd> is gone. Do not invent a
            // path under a daemon bypass root; fail closed and let the caller deny.
            return null
        } catch (e: java.io.FileNotFoundException) {
            return null
        } catch (e: Exception) {
            logger.severe { "Critical error during baseDir or /proc resolution for pid=$pid dirfd=$dirfd path=$pathStr: ${e.message}" }
            throw e
        }
    }
}
