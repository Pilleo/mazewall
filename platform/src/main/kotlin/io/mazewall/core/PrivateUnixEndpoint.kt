package io.mazewall.core

import io.mazewall.MazewallInternal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * A `0700` temporary directory plus a Unix socket path that fits in `sockaddr_un.sun_path`.
 *
 * Used by supervisor/profiler daemons and by process-portal workers. Callers own
 * deletion after the child no longer needs the path; [close] is a convenience.
 */
@MazewallInternal
public class PrivateUnixEndpoint private constructor(
    public val dir: Path,
    public val socketFileName: String,
    public val path: String,
) : AutoCloseable {
    public fun socketFile(): Path = dir.resolve(socketFileName)

    override fun close() {
        RealProcessLauncher.deleteIfExists(socketFile())
        RealProcessLauncher.deleteIfExists(dir)
    }

    public companion object {
        /** Exclusive max for `sun_path` including the trailing NUL used by [io.mazewall.ffi.networking.SupervisorSocketUtils.setupSockAddrUn]. */
        public const val MAX_SUN_PATH_BYTES: Int = 107

        public fun create(
            launcher: ProcessLauncher,
            dirPrefix: String,
            socketFileName: String,
        ): PrivateUnixEndpoint {
            val perms = PosixFilePermissions.fromString("rwx------")
            val attr = PosixFilePermissions.asFileAttribute(perms)
            var dir = launcher.createTempDirectory(dirPrefix, attr)
            var path = dir.resolve(socketFileName).toAbsolutePath().toString()
            if (utf8Length(path) > MAX_SUN_PATH_BYTES) {
                launcher.deleteIfExists(dir.resolve(socketFileName))
                launcher.deleteIfExists(dir)
                dir = launcher.createTempDirectory(Path.of("/tmp"), dirPrefix, attr)
                path = dir.resolve(socketFileName).toAbsolutePath().toString()
                require(utf8Length(path) <= MAX_SUN_PATH_BYTES) {
                    "Failed to generate a safe UNIX socket path (exceeds $MAX_SUN_PATH_BYTES bytes): $path"
                }
            }
            return PrivateUnixEndpoint(dir, socketFileName, path)
        }

        internal fun utf8Length(path: String): Int =
            path.toByteArray(StandardCharsets.UTF_8).size
    }
}
