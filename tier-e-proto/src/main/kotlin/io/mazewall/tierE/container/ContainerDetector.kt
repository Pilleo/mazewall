package io.mazewall.tierE.container

import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects container runtime metadata for a given PID by reading
 * `/proc/<pid>/cgroup` (cgroup v2 unified hierarchy). Returns null when
 * not containerized.
 */
public object ContainerDetector {

    public data class ContainerInfo(
        public val runtime: String,
        public val containerId: String,
    )

    private val CONTAINER_ID = Regex("[0-9a-f]{64}")

    private val RUNTIME_HINTS = listOf(
        "docker" to "docker",
        "containerd" to "containerd",
        "kubepods" to "k8s",
        "libpod" to "podman",
        "crio" to "crio",
    )

    /** Reads /proc/<pid>/cgroup and extracts container info if present. */
    public fun detect(pid: Long): ContainerInfo? {
        val lines = runCatching {
            Files.readAllLines(Path.of("/proc", pid.toString(), "cgroup"))
        }.getOrNull() ?: return null
        return detect(lines)
    }

    public fun detect(lines: List<String>): ContainerInfo? {
        for (line in lines) {
            val idMatch = CONTAINER_ID.find(line) ?: continue
            val id = idMatch.value
            val runtime = RUNTIME_HINTS.firstOrNull { hint ->
                line.contains(hint.first, ignoreCase = true)
            }?.second ?: return ContainerInfo("unknown", id)
            return ContainerInfo(runtime, id)
        }
        return null
    }
}
