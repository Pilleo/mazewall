package io.mazewall.profiler.tierE.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Filesystem access policy for the shared Unix-domain control endpoint. */
internal data class TierEControlSocketAccess(
    val groupId: Int? = null,
) {
    init {
        require(groupId == null || groupId >= 0) { "Tier E control socket group id must be non-negative" }
    }

    fun apply(path: Path) {
        groupId?.let { Files.setAttribute(path, "unix:gid", it) }
        Files.setPosixFilePermissions(path, if (groupId == null) OWNER_ONLY else OWNER_AND_GROUP)
    }

    companion object {
        private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val OWNER_AND_GROUP = OWNER_ONLY + PosixFilePermission.GROUP_READ + PosixFilePermission.GROUP_WRITE

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): TierEControlSocketAccess {
            val value = environment["TIER_E_SOCKET_GID"] ?: return TierEControlSocketAccess()
            return TierEControlSocketAccess(value.toIntOrNull() ?: error("TIER_E_SOCKET_GID must be an integer"))
        }
    }
}
