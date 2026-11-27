package io.mazewall.enforcer.internal.supervisor

/** Retained output for one daemon process. Only complete, size-checked lines reach [record]. */
internal class SupervisorLogTail {
    private val lines = ArrayDeque<String>(MAX_LINES)
    private var omittedLines = 0L

    fun record(line: String) {
        require(line.length <= MAX_LINE_CHARS)
        synchronized(lines) {
            if (lines.size == MAX_LINES) {
                lines.removeFirst()
                omittedLines++
            }
            lines.addLast(line)
        }
    }

    fun recordOversizedLine() {
        synchronized(lines) {
            omittedLines++
        }
    }

    fun reportLines(): List<String> =
        synchronized(lines) {
            buildList {
                if (omittedLines > 0) add("[$omittedLines daemon log lines omitted]")
                addAll(lines)
            }
        }

    companion object {
        const val MAX_LINES = 64
        const val MAX_LINE_CHARS = 2_048
    }
}
