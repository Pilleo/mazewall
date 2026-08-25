package io.mazewall.tierE.stress

import java.nio.file.Files
import java.nio.file.Path

/**
 * WP-05 / Gate G2 verification model (pure, kernel-free).
 *
 * Driver emits:
 *   `W <tid> <ctx> <startNs> <endNs>`  — one attributed scope window
 *   `Q <tid> <startNs>`                — quiet-from-here-on for this tid
 *
 * Daemon log lines (verbose mode):
 *   `E <tid> <nr> <ctx> <ktimeNs>`
 *
 * Classification per event (innermost matching window wins):
 *   inside window with matching ctx  -> inWindow
 *   inside window with OTHER ctx     -> INCORRECT (hard fail)
 *   no window, tid has quiet mark and ktime >= start -> LEAK (hard fail)
 *   no window at all                 -> OUTSIDE (hard fail: stale storage)
 *
 * Slack absorbs marker-return/first-syscall jitter on both edges.
 */
public data class Window(
        public val tid: Long,
        public val ctx: UInt,
        public val startNs: Long,
        public val endNs: Long,
    )

public data class QuietMark(public val tid: Long, public val startNs: Long)

public data class Event(
        public val tid: Long,
        public val syscallNr: Int,
        public val contextId: UInt,
        public val ktimeNs: Long,
    )

public object StressVerifier {

    /** Boundary fuzz absorbed when no strict window contains an event. */
    public const val DEFAULT_SLACK_NS: Long = 3_000_000L

    public data class Report(
        public val totalEvents: Int = 0,
        public val inWindow: Int = 0,
        public val outOfWindow: Int = 0,
        public val incorrectCtx: Int = 0,
        public val leakAfterQuiet: Int = 0,
        public val distinctTids: Int = 0,
        public val samples: List<String> = emptyList(),
    ) {
        public val passed: Boolean
            get() = incorrectCtx == 0 && leakAfterQuiet == 0 && outOfWindow == 0

        public fun render(): String =
            "total=$totalEvents inWindow=$inWindow outOfWindow=$outOfWindow " +
                "incorrectCtx=$incorrectCtx leakAfterQuiet=$leakAfterQuiet " +
                "tids=$distinctTids passed=$passed"
    }


    public fun parseEvents(lines: List<String>): List<Event> =
        lines.mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"))
            if (t.size == 5 && t[0] == "E") {
                Event(
                    tid = t[1].toLong(),
                    syscallNr = t[2].toInt(),
                    contextId = t[3].toUInt(),
                    ktimeNs = t[4].toLong(),
                )
            } else {
                null
            }
        }.toList()

    public fun parseDecls(lines: List<String>): Pair<List<Window>, List<QuietMark>> {
        val windows = mutableListOf<Window>()
        val quiets = mutableListOf<QuietMark>()
        lines.forEach { raw ->
            val t = raw.trim().split(Regex("\\s+"))
            when {
                t.size == 5 && t[0] == "W" -> windows.add(
                    Window(t[1].toLong(), t[2].toUInt(), t[3].toLong(), t[4].toLong()),
                )
                t.size == 3 && t[0] == "Q" -> quiets.add(QuietMark(t[1].toLong(), t[2].toLong()))
            }
        }
        return windows to quiets
    }

    /** Loads driver decl file + daemon verbose log from disk. */
    public fun verify(declFile: Path, logFile: Path): Report {
        val (windows, quiets) = parseDecls(Files.readAllLines(declFile))
        val events = parseEvents(Files.readAllLines(logFile))
        return verify(events, windows, quiets)
    }

    public fun verify(
        events: List<Event>,
        windows: List<Window>,
        quiets: List<QuietMark>,
        slackNs: Long = DEFAULT_SLACK_NS,
    ): Report {
        var inW = 0
        var outW = 0
        var incorrect = 0
        var leaked = 0
        val tids = HashSet<Long>()
        val samples = mutableListOf<String>()

        val byTidWindows = windows.groupBy { it.tid }
        val quietStart = HashMap<Long, Long>()
        for (q in quiets) {
            quietStart.merge(q.tid, q.startNs, ::minOf)
        }

        fun span(w: Window): Long = w.endNs - w.startNs

        for (e in events) {
            tids.add(e.tid)
            val candidates = byTidWindows[e.tid].orEmpty()
                .filter { e.ktimeNs >= it.startNs && e.ktimeNs <= it.endNs } // strict
            val boundary = candidates.isEmpty() // slack fallback only when needed
            val win = candidates.minByOrNull { span(it) }
                ?: byTidWindows[e.tid].orEmpty()
                    .filter {
                        e.ktimeNs >= it.startNs - slackNs && e.ktimeNs <= it.endNs + slackNs
                    }
                    .minByOrNull {
                        minOf(
                            kotlin.math.abs(e.ktimeNs - it.startNs),
                            kotlin.math.abs(e.ktimeNs - it.endNs),
                        )
                    }

            // Quiet-leak check precedes window attribution: a NONZERO context
            // observed after the tid's quiet mark is a stale-storage leak.
            val qs = quietStart[e.tid]
            if (win == null && qs != null && e.ktimeNs >= qs - slackNs &&
                e.contextId != 0u
            ) {
                leaked++
                if (samples.size < 10) {
                    samples.add("LEAK tid=${e.tid} nr=${e.syscallNr} ctx=${e.contextId}")
                }
                continue
            }

            when {
                win == null -> {
                    outW++
                    if (samples.size < 10) {
                        samples.add("OUT tid=${e.tid} nr=${e.syscallNr} ctx=${e.contextId}")
                    }
                }
                win.ctx != e.contextId -> {
                    incorrect++
                    val tag = if (boundary) "SLACK" else "STRICT"
                    if (samples.size < 10) {
                        samples.add(
                            "WRONG[$tag] tid=${e.tid} nr=${e.syscallNr} " +
                                "got=${e.contextId} want=${win.ctx}",
                        )
                    }
                }
                else -> inW++
            }
        }
        return Report(
            totalEvents = events.size,
            inWindow = inW,
            outOfWindow = outW,
            incorrectCtx = incorrect,
            leakAfterQuiet = leaked,
            distinctTids = tids.size,
            samples = samples,
        )
    }
}

