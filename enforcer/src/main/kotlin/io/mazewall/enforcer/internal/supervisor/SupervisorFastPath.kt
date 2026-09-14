package io.mazewall.enforcer.internal.supervisor

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
    fun resolveAbsolutePath(
        pid: Int,
        dirfd: TraceeDirFd,
        pathStr: String,
    ): Path? {
        val path = java.nio.file.Paths
            .get(pathStr)
        if (path.isAbsolute) {
            return when (val resolution = BypassPaths.resolveForPolicy(path)) {
                is BypassPaths.PathResolution.Resolved -> resolution.path
                is BypassPaths.PathResolution.Missing,
                is BypassPaths.PathResolution.Unsafe,
                -> null
            }
        }
        try {
            val baseDir = if (dirfd.value == AT_FDCWD) {
                java.nio.file.Paths
                    .get("/proc/$pid/cwd")
                    .toRealPath()
            } else {
                java.nio.file.Paths
                    .get("/proc/$pid/fd/${dirfd.value}")
                    .toRealPath()
            }
            return when (val resolution = BypassPaths.resolveForPolicy(baseDir.resolve(path))) {
                is BypassPaths.PathResolution.Resolved -> resolution.path
                is BypassPaths.PathResolution.Missing,
                is BypassPaths.PathResolution.Unsafe,
                -> null
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            // /proc/<pid>/cwd or /proc/<pid>/fd/<dirfd> is gone. Do not invent a
            // path under a daemon bypass root; fail closed and let the caller deny.
            return null
        } catch (_: java.io.FileNotFoundException) {
            return null
        } catch (expectedResolutionFailure: Exception) {
            logger.severe {
                "Critical error during baseDir or /proc resolution for pid=$pid dirfd=${dirfd.value} " +
                    "path=$pathStr: ${expectedResolutionFailure.message}"
            }
            throw expectedResolutionFailure
        }
    }
}
