package io.mazewall.profiler.tierE.stress

import java.nio.file.Files
import java.nio.file.Path

/** Pin before Java startup so every JVM-created worker inherits the requested target affinity. */
internal object TierEWorkloadLaunch {
    fun command(
        java: List<String>,
        targetCpu: String?,
        startupPin: Boolean,
    ): List<String> {
        if (!startupPin) return java
        val cpu = targetCpu?.toIntOrNull()
        require(cpu != null && cpu >= 0) { "startup-pinned Tier E workload requires a nonnegative CPU" }
        return listOf("taskset", "-c", cpu.toString()) + java
    }

    fun requireTargetThreadAffinity(
        threadCpus: Map<Int, String>,
        selectedCpu: Int,
    ) {
        check(selectedCpu >= 0 && threadCpus.isNotEmpty() && threadCpus.values.all { it == selectedCpu.toString() }) {
            "audited Tier E target JVM threads are not all pinned to workload CPU $selectedCpu: $threadCpus"
        }
    }

    fun verifyRunningTarget(
        processId: Long,
        targetCpu: String?,
    ) {
        val cpu = requireNotNull(targetCpu).toInt()
        val threadCpus = Files
            .list(Path.of("/proc/$processId/task"))
            .use { stream -> stream.toList() }
            .associate { task ->
                task.fileName.toString().toInt() to Files
                    .readAllLines(task.resolve("status"))
                    .single { it.startsWith("Cpus_allowed_list:") }
                    .substringAfter(':')
                    .trim()
            }
        requireTargetThreadAffinity(threadCpus, cpu)
        println("tier-e-pre-gate-affinity: workload_cpu=$cpu threads=${threadCpus.size} launched_pinned=true")
    }
}
