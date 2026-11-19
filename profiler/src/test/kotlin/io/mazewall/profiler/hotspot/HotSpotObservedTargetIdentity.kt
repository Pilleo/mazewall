package io.mazewall.profiler.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.internal.hotspot.ProcHotSpotTargetIdentity
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_MEMBERSHIP_BYTES = 65536

/** Test-only discovery requires the target's cgroup to be visible in this host mount. */
internal fun observedTargetIdentity(pid: Pid): HotSpotObservationTarget {
    val birth = ProcHotSpotTargetIdentity.startTicks(pid)
    val proc = Path.of("/proc/${pid.value}")
    val membership = Files.newInputStream(proc.resolve("cgroup")).use { it.readNBytes(MAX_MEMBERSHIP_BYTES + 1) }
    require(membership.size <= MAX_MEMBERSHIP_BYTES) { "Target cgroup membership exceeds limit" }
    val cgroup = observedCgroupPath(membership.toString(Charsets.UTF_8))
    val root = Path.of("/sys/fs/cgroup")
    require(Files.getFileStore(root).type() == "cgroup2") { "Acceptance requires a visible cgroupv2 mount" }
    val visible = root.resolve(cgroup.toString().removePrefix("/"))
    require(Files.isDirectory(visible) && Files.getFileStore(visible).type() == "cgroup2") { "Target cgroup is not visible" }
    val inode = (Files.getAttribute(visible, "unix:ino") as Long).toULong()
    val mount = observedMountNamespace(Files.readSymbolicLink(proc.resolve("ns/mnt")).toString())
    require(inode > 0uL && ProcHotSpotTargetIdentity.startTicks(pid) == birth) { "Target identity changed during discovery" }
    return HotSpotObservationTarget(pid, birth, inode, mount)
}

internal fun observedCgroupPath(text: String): Path {
    val unified = text.lineSequence().filter { it.startsWith("0::") }.toList()
    require(unified.size == 1) { "Expected exactly one unified cgroup membership" }
    val raw = unified.single().removePrefix("0::")
    require(raw.startsWith('/') && '\u0000' !in raw) { "Cgroup path must be absolute" }
    require(raw == "/" || raw.substring(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Cgroup path must not escape or normalize" }
    return Path.of(raw)
}

internal fun observedMountNamespace(text: String): ULong {
    require(Regex("mnt:\\[[0-9]+]").matches(text)) { "Expected an exact mount namespace link" }
    return requireNotNull(text.removePrefix("mnt:[").removeSuffix("]").toULongOrNull()) { "Invalid mount namespace inode" }
        .also { require(it > 0uL) { "Missing mount namespace inode" } }
}
