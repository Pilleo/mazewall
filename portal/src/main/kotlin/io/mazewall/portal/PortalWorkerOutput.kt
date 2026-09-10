package io.mazewall.portal

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/** Drains one owned child stream with bounded lines and retention; never writes child data to disk. */
internal class PortalWorkerOutput(
    input: InputStream,
) {
    val ready = CountDownLatch(1)

    @Volatile var readinessObserved = false
        private set
    private val lines = ArrayDeque<String>()
    private var droppedLines = 0L
    private var truncatedLines = 0L
    private var failure: IOException? = null
    val thread: Thread = Thread.ofPlatform().daemon().name("portal-worker-output").start {
        try {
            input.bufferedReader().use { reader ->
                val line = StringBuilder(MAX_LINE)
                var truncated = false
                while (true) {
                    val next = reader.read()
                    if (next == -1) {
                        if (line.isNotEmpty() || truncated) record(line.toString(), truncated, complete = false)
                        break
                    }
                    if (next == '\n'.code) {
                        record(line.toString().removeSuffix("\r"), truncated, complete = true)
                        line.setLength(0)
                        truncated = false
                    } else if (line.length < MAX_LINE) {
                        val character = next.toChar()
                        line.append(if (character.isISOControl() && character != '\r') '?' else character)
                    } else {
                        truncated = true
                    }
                }
            }
        } catch (error: IOException) {
            synchronized(this) { failure = error }
        } finally {
            ready.countDown()
        }
    }

    @Synchronized
    fun snapshot(): PortalWorkerDiagnostics = PortalWorkerDiagnostics(lines.toList(), droppedLines, truncatedLines, failure)

    @Synchronized
    private fun record(
        line: String,
        truncated: Boolean,
        complete: Boolean,
    ) {
        if (complete && !truncated && line == READY) {
            readinessObserved = true
            ready.countDown()
        }
        if (lines.size == MAX_LINES) {
            lines.removeFirst()
            droppedLines++
        }
        lines.addLast(line.replace('\r', '?'))
        if (truncated) truncatedLines++
    }

    private companion object {
        const val MAX_LINES = 64
        const val MAX_LINE = 1024
        const val READY = "MAZEWALL_PORTAL_WORKER_READY"
    }
}

internal data class PortalWorkerDiagnostics(
    val lines: List<String>,
    val droppedLines: Long,
    val truncatedLines: Long,
    val failure: IOException?,
)
