package io.mazewall.enforcer.supervisor

/**
 * Procfs inspection helpers extracted from [SupervisorSessionHandler] (issue-20260823-171956,
 * slice 1 of the decomposition). Pure /proc reading with conservative fallbacks; no engine state.
 */
internal object ProcFsInspector {
    private const val PPID_FIELD_AFTER_COMM = 1

    internal sealed interface PidObservation {
        data class Observed(
            val value: Int,
        ) : PidObservation

        data object Unavailable : PidObservation

        data object Malformed : PidObservation
    }

    /** Reads `Tgid:` from /proc/<tid>/status, falling back to [tid] itself. */
    fun getTgid(tid: Int): Int = readStatusLine(tid, "Tgid:")?.substringAfter("Tgid:")?.trim()?.toIntOrNull() ?: tid

    /**
     * Reads the parent pid from /proc/<pid>/stat (field 4, after the parenthesized comm),
     * falling back to 0 when unavailable.
     */
    fun getPpid(pid: Int): Int = (readPpid(pid) as? PidObservation.Observed)?.value ?: 0

    /** Preserves whether `/proc` supplied a pid instead of collapsing failure into zero. */
    fun readPpid(pid: Int): PidObservation {
        try {
            val statFile = java.io.File("/proc/$pid/stat")
            if (statFile.exists()) {
                return parsePpid(statFile.readText())
            }
        } catch (ignored: Exception) {
        }
        return PidObservation.Unavailable
    }

    internal fun parsePpid(content: String): PidObservation {
        val close = content.lastIndexOf(')')
        if (close < 0) return PidObservation.Malformed
        val parts = content.substring(close + 1).trim().split(Regex("\\s+"))
        val ppid = parts.getOrNull(PPID_FIELD_AFTER_COMM)?.toIntOrNull() ?: return PidObservation.Malformed
        return PidObservation.Observed(ppid)
    }

    private fun readStatusLine(
        tid: Int,
        prefix: String,
    ): String? =
        try {
            java.io
                .File("/proc/$tid/status")
                .takeIf(java.io.File::exists)
                ?.bufferedReader()
                ?.useLines { lines -> lines.firstOrNull { it.startsWith(prefix) } }
        } catch (_: Exception) {
            null
        }
}
