package io.mazewall.profiler.tierE.daemon

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeFileSystem
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Immutable evidence policy, separate from control-socket access. Apply only to a closed owned temp. */
internal data class TierEEvidenceFileAccess(
    val groupId: Int? = null,
) {
    init {
        require(groupId == null || groupId >= 0) { "Tier E evidence GID must be 0..2147483647" }
    }

    fun apply(
        path: Path,
        native: NativeFileSystem = LinuxNative.fileSystem,
        setGroup: (Path, Int) -> Unit = { file, gid -> Files.setAttribute(file, "unix:gid", gid, NOFOLLOW_LINKS) },
        setMode: (Path, Set<PosixFilePermission>) -> Unit = { file, mode -> Files.setPosixFilePermissions(file, mode) },
    ) {
        if (groupId != null) {
            // chmod 0640 activates inherited named-user ACL entries unless they are removed first.
            NativeArena.ofConfined().use { arena ->
                val result = native.removeExtendedAttribute(arena.allocateFrom(path.toString()), arena.allocateFrom("system.posix_acl_access"))
                when (result) {
                    is LinuxNative.SyscallResult.Success -> Unit
                    is LinuxNative.SyscallResult.Error -> if (result.errno != NativeConstants.ENODATA) result.throwErrno("clear evidence access ACL")
                }
            }
            setGroup(path, groupId)
        }
        setMode(path, if (groupId == null) OWNER_ONLY else OWNER_ONLY + PosixFilePermission.GROUP_READ)
    }

    companion object {
        val OWNER_ONLY: Set<PosixFilePermission> = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): TierEEvidenceFileAccess {
            val raw = environment["TIER_E_EVIDENCE_GID"] ?: return TierEEvidenceFileAccess()
            require(raw.matches(Regex("[0-9]+"))) { "TIER_E_EVIDENCE_GID must be decimal 0..2147483647" }
            return TierEEvidenceFileAccess(requireNotNull(raw.toIntOrNull()) { "TIER_E_EVIDENCE_GID must be 0..2147483647" })
        }
    }
}
