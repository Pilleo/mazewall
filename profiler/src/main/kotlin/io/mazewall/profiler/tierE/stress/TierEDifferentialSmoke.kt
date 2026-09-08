package io.mazewall.profiler.tierE.stress

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Privileged acceptance gate comparing Tier E with the USER_NOTIF stack oracle. */
public object TierEDifferentialSmoke {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size in 2..3) { "usage: <agent.so> <workload-classpath> [iterations]" }
        val iterations = args.getOrElse(2) { "100" }
        val definitions = Files.createTempFile("mazewall-tier-e-differential", ".bin")
        Files.deleteIfExists(definitions)
        try {
            val tierE = runJava(
                "io.mazewall.profiler.tierE.stress.TierEKernelSmoke",
                args[0],
                "auto",
                args[1],
                definitions.toString(),
                "platform",
                iterations,
            )
            val oracle = runJava("io.mazewall.profiler.tierE.stress.UserNotifOracleSmoke", iterations)
            val tierNanos = metric(tierE, "workloadNs")
            val oracleNanos = metric(oracle, "workloadNs")
            val resolved = metric(tierE, "resolved")
            check(metric(tierE, "wrong") == 0L) { "Tier E produced a wrong attribution:\n$tierE" }
            check(metric(tierE, "lost") == 0L) { "Tier E lost kernel observations:\n$tierE" }
            check(resolved >= iterations.toLong() * MIN_RESOLVED_PER_ITERATION) {
                "Tier E workload-stack coverage regressed: resolved=$resolved iterations=$iterations"
            }
            check(tierNanos < oracleNanos) {
                "Tier E was not faster than USER_NOTIF: tierE=$tierNanos oracle=$oracleNanos"
            }
            println(
                "tier-e-differential: tierENs=$tierNanos userNotifNs=$oracleNanos " +
                "speedup=${String.format(Locale.ROOT, "%.2f", oracleNanos.toDouble() / tierNanos)}x",
            )
        } finally {
            Files.deleteIfExists(definitions)
            Files.deleteIfExists(Path.of("$definitions.gate"))
        }
    }

    private fun runJava(
        mainClass: String,
        vararg arguments: String,
    ): String {
        val command = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            mainClass,
        ) +
            arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "$mainClass failed:\n$output" }
        print(output)
        return output
    }

    private fun metric(
        output: String,
        name: String,
    ): Long =
        requireNotNull(
            Regex("(?:^|\\s)$name=(\\d+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull(),
        ) {
            "missing $name in output:\n$output"
        }

    private const val MIN_RESOLVED_PER_ITERATION = 5
}
