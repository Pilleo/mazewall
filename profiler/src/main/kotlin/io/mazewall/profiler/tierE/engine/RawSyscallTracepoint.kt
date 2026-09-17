package io.mazewall.profiler.tierE.engine

import java.nio.file.Files
import java.nio.file.Path

/** Admission boundary for the ordinary `raw_syscalls/sys_enter` tracepoint. */
internal data class RawSyscallTracepoint(
    val id: Int,
) {
    internal companion object {
        private val roots = listOf(
            Path.of("/sys/kernel/tracing/events/raw_syscalls/sys_enter"),
            Path.of("/sys/kernel/debug/tracing/events/raw_syscalls/sys_enter"),
        )

        fun discover(): RawSyscallTracepoint {
            val directory = roots.firstOrNull(Files::isDirectory)
                ?: error("raw_syscalls/sys_enter tracepoint is unavailable")
            validateFormat(Files.readString(directory.resolve("format")))
            val id = Files.readString(directory.resolve("id")).trim().toIntOrNull()
                ?: error("raw_syscalls/sys_enter tracepoint has an invalid id")
            require(id > 0) { "raw_syscalls/sys_enter tracepoint id must be positive" }
            return RawSyscallTracepoint(id)
        }

        internal fun validateFormat(format: String) {
            val fields = format
                .lineSequence()
                .map(String::trim)
                .filter { it.startsWith("field:") }
                .toList()
            val id = fields.singleOrNull { it.contains(" id;") }
            val args = fields.singleOrNull { it.contains(" args[6];") }
            require(id != null && fieldOffset(id) == 8 && fieldSize(id) == Long.SIZE_BYTES) {
                "raw_syscalls/sys_enter must expose id at offset 8 as u64"
            }
            require(args != null && fieldOffset(args) == 16 && fieldSize(args) == Long.SIZE_BYTES * 6) {
                "raw_syscalls/sys_enter must expose args[6] at offset 16"
            }
        }

        private fun fieldOffset(field: String): Int = fieldValue(field, "offset")

        private fun fieldSize(field: String): Int = fieldValue(field, "size")

        private fun fieldValue(
            field: String,
            name: String,
        ): Int =
            Regex("$name:(\\d+);")
                .find(field)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            ?: throw IllegalArgumentException("tracepoint field is missing $name: $field")
    }
}
