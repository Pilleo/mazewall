package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path

/** Refuse a measured CPU plan outside the current local caller's effective Linux affinity. */
public object TierEAllowedCpuVerifier {
    private const val MAX_CPU_ID = 65_535

    public fun requireAvailable(
        requested: String,
        allowed: String = currentAllowed(),
    ) {
        val parts = requested.split(',')
        require(parts.size in 1..64) { "Tier E CPU selection is empty or unbounded" }
        val selected = parts.map { text ->
            text.toIntOrNull()?.takeIf { it in 0..MAX_CPU_ID && it.toString() == text }
                ?: throw IllegalArgumentException("Invalid measured CPU: $text")
        }
        require(selected.size == selected.toSet().size) { "Duplicate measured CPU" }
        val permitted = mutableSetOf<Int>()
        for (part in allowed.trim().split(',')) {
            val bounds = part.split('-')
            require(bounds.size in 1..2) { "Invalid effective CPU range" }
            val from = bounds[0].toIntOrNull()
            val to = bounds.last().toIntOrNull()
            require(from != null && to != null && from in 0..MAX_CPU_ID && to in from..MAX_CPU_ID) {
                "Invalid effective CPU range: $part"
            }
            for (cpu in from..to) permitted += cpu
        }
        require(selected.all { it in permitted }) { "Selected Tier E CPU is unavailable in local effective affinity" }
    }

    private fun currentAllowed(): String =
        Files.newBufferedReader(Path.of("/proc/self/status")).use { input ->
        input
            .lineSequence()
            .firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Linux effective CPU set unavailable")
    }
}
