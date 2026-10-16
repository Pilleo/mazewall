package io.mazewall.profiler.tierE.engine

import java.nio.file.Files
import java.nio.file.Path

/** Admission boundary for `sched/sched_process_exec`, which fires only after a successful exec. */
internal data class SchedProcessExecTracepoint(
    val id: Int,
) {
    internal companion object {
        private val roots = listOf(
            Path.of("/sys/kernel/tracing/events/sched/sched_process_exec"),
            Path.of("/sys/kernel/debug/tracing/events/sched/sched_process_exec"),
        )

        fun discover(): SchedProcessExecTracepoint {
            val directory = roots.firstOrNull(Files::isDirectory)
                ?: error("sched/sched_process_exec tracepoint is unavailable")
            val id = Files.readString(directory.resolve("id")).trim().toIntOrNull()
                ?: error("sched/sched_process_exec tracepoint has an invalid id")
            require(id > 0) { "sched/sched_process_exec tracepoint id must be positive" }
            return SchedProcessExecTracepoint(id)
        }
    }
}
