package io.mazewall.enforcer.supervisor

/**
 * Procfs inspection helpers extracted from [SupervisorSessionHandler] (issue-20260823-171956,
 * slice 1 of the decomposition). Pure /proc reading with conservative fallbacks; no engine state.
 */
internal object ProcFsInspector {
    /** Reads `Tgid:` from /proc/<tid>/status, falling back to [tid] itself. */
    fun getTgid(tid: Int): Int {
        try {
            val statusFile = java.io.File("/proc/$tid/status")
            if (statusFile.exists()) {
                statusFile.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("Tgid:")) {
                            return line.substringAfter("Tgid:").trim().toInt()
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return tid
    }

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
                if (parts.size >= 3) {
                    return parts[2].toInt()
                }
            }
        } catch (ignored: Exception) {}
        return 0
    }
}
