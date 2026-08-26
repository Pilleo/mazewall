package io.mazewall.profiler.tierE.stress

import java.nio.file.Path
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    var decl: Path? = null
    var log: Path? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--decl" -> {
                require(i + 1 < args.size); decl = Path.of(args[++i])
            }
            "--log" -> {
                require(i + 1 < args.size); log = Path.of(args[++i])
            }
            else -> {
                System.err.println("usage: StressVerifier --decl <file> --log <file>")
                exitProcess(2)
            }
        }
        i++
    }
    val d = requireNotNull(decl) { "--decl required" }
    val l = requireNotNull(log) { "--log required" }
    val report = StressVerifier.verify(d, l)
    println("REPORT " + report.render())
    report.samples.forEach { println("SAMPLE $it") }
    exitProcess(if (report.passed) 0 else 1)
}
