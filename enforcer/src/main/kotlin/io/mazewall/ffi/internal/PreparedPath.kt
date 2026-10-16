package io.mazewall.ffi.internal

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A genuine O_PATH acquisition, not a pathname promise. Linux proc metadata is required. */
internal class PreparedPath private constructor(
    val requested: Path,
    val resolved: Path,
    val descriptor: FileDescriptor<FileDescriptorRole.OPath, FdState.Open, FdOwnership.Owned>,
    val directory: Boolean,
    private val key: Any,
    private val native: NativeEngine,
) : AutoCloseable {
    val identity: String get() = "$requested\u0000$resolved\u0000$key\u0000$directory"

    fun validate() {
        check(descriptor.isLiveForIo()) { "Prepared descriptor has retired: $requested" }
        check(requested.toRealPath() == resolved) { "Prepared symlink resolution changed: $requested" }
        val current = Files.readAttributes(requested, BasicFileAttributes::class.java)
        val pinned = Files.readAttributes(descriptorPath(), BasicFileAttributes::class.java)
        check(current.fileKey() == key && pinned.fileKey() == key && current.isDirectory == directory) {
            "Prepared filesystem object was replaced: $requested"
        }
    }

    fun descriptorPath(): Path = Path.of("/proc/self/fd/${descriptor.value}")

    override fun close() {
        if (descriptor.isLiveForIo()) {
            try {
                native.fileSystem.close(descriptor).getOrThrow("close prepared resource $requested")
            } finally {
                descriptor.markClosed()
            }
        }
    }

    companion object {
        fun open(
            path: Path,
            native: NativeEngine,
        ): PreparedPath {
            val requested = path.toAbsolutePath().normalize()
            val resolved = requested.toRealPath()
            val before = Files.readAttributes(resolved, BasicFileAttributes::class.java)
            require(before.isDirectory || before.isRegularFile) { "Prepared resource must be a regular file or directory: $requested" }
            val key = checkNotNull(before.fileKey()) { "Filesystem has no stable object identity: $requested" }
            val descriptor = NativeArena.ofConfined().use { arena ->
                native.fileSystem
                    .open(arena.allocateFrom(resolved.toString()), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW))
                    .getFdOrThrow("open prepared resource $requested", FileDescriptorRole.OPath)
            }
            val result = PreparedPath(requested, resolved, descriptor, before.isDirectory, key, native)
            return ResourceRetirement.runWithRollback({
                result.validate()
                result
            }, { result.close() })
        }
    }
}
