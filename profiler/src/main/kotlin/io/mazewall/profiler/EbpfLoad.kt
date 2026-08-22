package io.mazewall.profiler

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether this process can load tracing eBPF programs.
 *
 * uid 0 inside a user namespace is not enough: `bpf(2)` needs capabilities in the
 * initial host user namespace.
 */
public sealed interface EbpfLoad {
    public data object Available : EbpfLoad
    public data object UserNamespaceRoot : EbpfLoad
    public data class Denied(val reason: String) : EbpfLoad
}

public object EbpfCapability {
    private const val CAP_SYS_ADMIN = 21
    private const val CAP_PERFMON = 38
    private const val CAP_BPF = 39

    public fun probe(
        selfNsUser: Path = Path.of("/proc/self/ns/user"),
        initNsUser: Path = Path.of("/proc/1/ns/user"),
        status: Path = Path.of("/proc/self/status"),
        uidMap: Path = Path.of("/proc/self/uid_map"),
        euid: Int? = null,
    ): EbpfLoad {
        val statusText = runCatching { Files.readString(status) }.getOrNull()
        val resolvedEuid = euid ?: statusText?.let { parseEuid(it) } ?: 1
        val inInitNs = sameNamespace(selfNsUser, initNsUser, uidMap)
        val capEff = statusText?.let { parseCapEff(it) }
        val hasBpfCaps = capEff != null &&
            (hasCap(capEff, CAP_BPF) || hasCap(capEff, CAP_SYS_ADMIN) || hasCap(capEff, CAP_PERFMON))
        if (inInitNs && hasBpfCaps) {
            return EbpfLoad.Available
        }
        if (!inInitNs && resolvedEuid == 0) {
            return EbpfLoad.UserNamespaceRoot
        }
        if (!inInitNs) {
            return EbpfLoad.Denied("not in the initial user namespace")
        }
        return EbpfLoad.Denied("missing CAP_BPF/CAP_PERFMON/CAP_SYS_ADMIN (CapEff=${capEff ?: "unreadable"})")
    }

    internal fun parseCapEff(statusText: String): Long? {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("CapEff:") } ?: return null
        val hex = line.substringAfter(':').trim()
        return hex.toLongOrNull(16)
    }

    internal fun parseInitUserNamespace(uidMapText: String): Boolean {
        val lines = uidMapText.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val parts = lines[0].trim().split(Regex("\\s+"))
        if (parts.size < 3) return false
        val inside = parts[0].toLongOrNull() ?: return false
        val outside = parts[1].toLongOrNull() ?: return false
        val length = parts[2].toLongOrNull() ?: return false
        return inside == 0L && outside == 0L && length >= 4294967295L
    }

    internal fun hasCap(capEff: Long, cap: Int): Boolean = (capEff ushr cap) and 1L == 1L

    internal fun parseEuid(statusText: String): Int? {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return null
        val parts = line.substringAfter(':').trim().split(Regex("\\s+"))
        return parts.getOrNull(1)?.toIntOrNull() ?: parts.getOrNull(0)?.toIntOrNull()
    }

    private fun sameNamespace(selfNs: Path, initNs: Path, uidMap: Path): Boolean {
        val fromUidMap = runCatching {
            if (Files.exists(uidMap)) {
                parseInitUserNamespace(Files.readString(uidMap))
            } else {
                null
            }
        }.getOrNull()

        if (fromUidMap != null) {
            return fromUidMap
        }

        return runCatching {
            Files.isSameFile(selfNs, initNs)
        }.getOrDefault(false)
    }
}
