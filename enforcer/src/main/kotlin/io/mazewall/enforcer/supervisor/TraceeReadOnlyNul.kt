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

    context(arena: NativeArena)
    fun find(tid: Tid): Long? {
        val maps = File("/proc/${tid.value}/maps")
        if (!maps.isFile) {
            return null
        }
        val lines = try {
            maps.readLines()
        } catch (_: Exception) {
            return null
        }
        for (line in lines) {
            val match = MAP_LINE.find(line) ?: continue
            val perms = match.groupValues[3]
            if (!perms.startsWith("r") || perms[1] == 'w' || perms[2] != 'x') {
                continue
            }
            val start = match.groupValues[1].toLongOrNull(16) ?: continue
            val end = match.groupValues[2].toLongOrNull(16) ?: continue
            if (end <= start) {
                continue
            }
            val len = minOf(64, (end - start).toInt())
            val bytes = try {
                SupervisorProcessMemoryReader.readBytes(tid, start, len)
            } catch (_: Exception) {
                null
            } ?: continue
            val nulAt = bytes.indexOf(0)
            if (nulAt >= 0) {
                return start + nulAt
            }
        }
        return null
    }
}
