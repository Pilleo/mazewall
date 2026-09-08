package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorProcessMemoryReader
import java.io.File

/**
 * Locates a NUL byte in a read-only executable mapping of the tracee (typically [vdso]).
 * execveat(AT_EMPTY_PATH) must point pathname at memory the tracee cannot overwrite.
 */
internal object TraceeReadOnlyNul {
    private val MAP_LINE = Regex("""^([0-9a-fA-F]+)-([0-9a-fA-F]+)\s+(r[w-][x-])p\s+""")

    context(arena: NativeArena) fun find(tid: Tid): Long? =
        readMaps(tid)?.firstNotNullOfOrNull { line ->
            readOnlyExecutableMapping(line)?.let { mapping -> firstNulAddress(tid, mapping) }
        }

    private fun readMaps(tid: Tid): List<String>? {
        val maps = File("/proc/${tid.value}/maps")
        if (!maps.isFile) return null
        return try {
            maps.readLines()
        } catch (_: Exception) {
            null
        }
    }

    private fun readOnlyExecutableMapping(line: String): TraceeMapping? {
        val match = MAP_LINE.find(line) ?: return null
        val perms = match.groupValues[3]
        if (perms[1] == 'w') return null
        val start = match.groupValues[1].toLongOrNull(16) ?: return null
        val end = match.groupValues[2].toLongOrNull(16) ?: return null
        return TraceeMapping(start, end).takeIf { it.end > it.start }
    }

    context(arena: NativeArena) private fun firstNulAddress(
        tid: Tid,
        mapping: TraceeMapping,
    ): Long? {
        val bytes = try {
            SupervisorProcessMemoryReader.readBytes(tid, mapping.start, minOf(64, (mapping.end - mapping.start).toInt()))
        } catch (_: Exception) {
            return null
        } ?: return null
        return bytes.indexOf(0).takeIf { it >= 0 }?.let { mapping.start + it }
    }

    private data class TraceeMapping(
        val start: Long,
        val end: Long,
    )
}
