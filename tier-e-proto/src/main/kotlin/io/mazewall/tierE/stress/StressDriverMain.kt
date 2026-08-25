package io.mazewall.tierE.stress

import io.mazewall.tierE.ffi.MarkerBinder
import io.mazewall.tierE.ffi.PosixFfi
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * WP-05 stress driver (Gate G2 producer side).
 *
 * Runs scripted scope windows on real JVM platform threads and writes the
 * ground-truth declaration stream (`W`/`Q` lines) for [StressVerifier].
 * Scenarios: churn, nesting, executor-reuse (incl. exception path).
 *
 * All contexts are UNIQUE PER TASK so any stale-bleed is detectable as an
 * event outside its window or with a wrong id — never absorbed by an
 * allowed-set.
 */
public object StressDriverMain {

    private val posix = PosixFfi()
    private val decls = StringBuilder()

    @Synchronized
    private fun record(line: String) {
        decls.append(line).append('\n')
    }

    // Lock-free unique context ids; uniqueness is the only invariant here.
    private val baseCtx = AtomicInteger(100)

    private fun nextCtx(): UInt = baseCtx.incrementAndGet().toUInt()

    /** One attributed burst; returns wall window in nanoTime domain. */
    private fun scoped(marker: MarkerBinder, ctx: UInt, calls: Int, tid: Long): Window {
        marker.mark(ctx)
        val start = System.nanoTime()
        repeat(calls) { posix.getpid() }
        val end = System.nanoTime()
        marker.mark(0u) // reset to UNKNOWN: idle syscalls must be suppressed
        return Window(tid, ctx, start - SLACK, end + SLACK)
    }

    public const val SLACK: Long = 3_000_000L // 3 ms, mirrors verifier

    @JvmStatic
    public fun main(argv: Array<String>) {
        var soPath = "build/libmazewall_context_usdt.so"
        var workers = 8
        var churnBatches = 2000
        var nestThreads = 8
        var execPool = 4
        var execTasks = 80
        var initialWaitMs = 1500L
        var gatePath: String? = null
        var i = 0
        while (i < argv.size) {
            when (argv[i]) {
                "--marker-so" -> soPath = argv[++i]
                "--workers" -> workers = argv[++i].toInt()
                "--churn-batches" -> churnBatches = argv[++i].toInt()
                "--nest-threads" -> nestThreads = argv[++i].toInt()
                "--exec-pool" -> execPool = argv[++i].toInt()
                "--exec-tasks" -> execTasks = argv[++i].toInt()
                "--initial-wait-ms" -> initialWaitMs = argv[++i].toLong()
                "--gate" -> gatePath = argv[++i]
                else -> {
                    System.err.println("unknown arg ${argv[i]}")
                    exitUsage()
                }
            }
            i++
        }

        MarkerBinder(soPath).use { marker ->
            Thread.sleep(initialWaitMs)
            // Hold here until the tracer attaches (gate file appears).
            gatePath?.let { gate ->
                val deadline = System.currentTimeMillis() + 60_000
                while (!Files.exists(Path.of(gate)) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25)
                }
            }

            // S1 churn: thread-per-batch creation/destruction at scale.
            repeat(churnBatches) {
                val t = Thread {
                    val ctx = nextCtx()
                    val tid = Thread.currentThread().threadId()
                    val w = scoped(marker, ctx, 3, tid)
                    synchronized(decls) { record("W $tid ${w.ctx} ${w.startNs} ${w.endNs}") }
                }
                t.start()
                t.join(10_000)
            }
            System.err.println("[stress] churn batches=$churnBatches")

            // S2 nesting: innermost-wins across T threads.
            val nestPool = Executors.newFixedThreadPool(nestThreads)
            repeat(nestThreads) {
                nestPool.submit {
                    val tid = Thread.currentThread().threadId()
                    val outer = nextCtx()
                    marker.mark(outer)
                    val oStart = System.nanoTime() - SLACK
                    val inner = nextCtx()
                    marker.mark(inner)
                    val iStart = System.nanoTime() - SLACK
                    repeat(4) { posix.getpid() }
                    val iEnd = System.nanoTime() + SLACK
                    marker.mark(outer)
                    repeat(4) { posix.getpid() }
                    val oEnd = System.nanoTime() + SLACK
                    marker.mark(0u)
                    synchronized(decls) {
                        record("W $tid $outer $oStart $oEnd")
                        record("W $tid $inner $iStart $iEnd")
                    }
                }
            }
            nestPool.shutdown()
            nestPool.awaitTermination(60, TimeUnit.SECONDS)

            // S3 executor reuse + exception path: fixed pool, unique per task,
            // one task throws mid-scope (finally restores previous context).
            val pool = Executors.newFixedThreadPool(execPool)
            val failures = (0 until execTasks).map { idx ->
                pool.submit<Unit> {
                    val tid = Thread.currentThread().threadId()
                    val ctx = nextCtx()
                    marker.mark(ctx)
                    val start = System.nanoTime() - SLACK
                    try {
                        posix.getpid()
                        if (idx % 7 == 3) throw IllegalStateException("scripted")
                        posix.getpid()
                    } catch (e: IllegalStateException) {
                        // scripted failure mid-scope
                    } finally {
                        val end = System.nanoTime() + SLACK
                        synchronized(decls) { record("W $tid $ctx $start $end") }
                        marker.mark(0u)
                        synchronized(decls) { record("Q $tid ${System.nanoTime()}") }
                    }
                }
            }
            for (f in failures) f.get(60, TimeUnit.SECONDS)
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)

            // Quiesce marks: everything from here on must be silent per tid.
            System.err.println("[stress] complete")
        }
        println(decls.toString())
    }

    private fun exitUsage(): Nothing {
        System.err.println(
            "usage: StressDriverMain [--marker-so p] [--workers n] [--churn-batches n] " +
                "[--nest-threads n] [--exec-pool n] [--exec-tasks n] [--initial-wait-ms n]",
        )
        kotlin.system.exitProcess(2)
    }
}
