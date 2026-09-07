package io.mazewall.enforcer.supervisor

/**
 * Procfs inspection helpers extracted from [SupervisorSessionHandler] (issue-20260823-171956,
 * slice 1 of the decomposition). Pure /proc reading with conservative fallbacks; no engine state.
 */
internal object ProcFsInspector {
    private const val PPID_FIELD_AFTER_COMM = 2

    /** Reads `Tgid:` from /proc/<tid>/status, falling back to [tid] itself. */
    fun getTgid(tid: Int): Int = readStatusLine(tid, "Tgid:")?.substringAfter("Tgid:")?.trim()?.toIntOrNull() ?: tid

    /**
     * Reads the parent pid from /proc/<pid>/stat (field 4, after the parenthesized comm),
     * falling back to 0 when unavailable.
     */
    fun getPpid(pid: Int): Int {
        try {
            val statFile = java.io.File("/proc/$pid/stat")
            if (statFile.exists()) {
                val content = statFile.readText()
                // Format: pid (comm) state ppid ...
                val parts = content.substringAfterLast(')').split(' ')
                if (parts.size > PPID_FIELD_AFTER_COMM) {
                    return parts[PPID_FIELD_AFTER_COMM].toInt()
                }
            }
        } catch (ignored: Exception) {
            }
        return 0
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
