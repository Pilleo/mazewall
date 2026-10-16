package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.time.Duration

/** Explicit standalone decoder/drain experiments; every fork and sample must be accounted for. */
public object TierERingMicrobenchmarkRunner {
    private val variants = mapOf(
        "decoder" to listOf("direct", "scalar-reads", "event-construction"),
        "mapped-drain" to listOf("empty", "single", "batch-16"),
    )

    public fun verify(
        kind: String,
        results: Path,
        forks: Int,
        samples: Int,
    ): Boolean {
        val choices = variants[kind] ?: throw IllegalArgumentException("Unknown Tier E ring benchmark")
        val rows = Files
            .readAllLines(results)
            .filter(String::isNotBlank)
            .map { line ->
            Json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("Invalid ring benchmark row")
        }.filter { it["kind"] == JsonPrimitive("sample") }
        require(rows.size == Math.multiplyExact(Math.multiplyExact(forks, samples), choices.size)) { "Ring benchmark sample count mismatch" }
        require(
            rows.all { row ->
            val mode = row["variant"]?.toString()?.trim('"')
            mode in choices &&
                if (kind == "mapped-drain") {
                val drains = (row["drains"] as? JsonPrimitive)?.longOrNull
                val records = (row["records"] as? JsonPrimitive)?.longOrNull
                drains != null &&
                    drains > 0 &&
                    records == when (mode) {
                    "empty" -> 0L
                    "single" -> drains
                    else -> Math.multiplyExact(drains, 16)
                }
            } else {
                (row["checksum"] as? JsonPrimitive)?.longOrNull != null
            }
        },
        ) { "Ring benchmark record-count or variant mismatch" }
        if (kind == "decoder") require(rows.map { it.getValue("checksum") }.distinct().size == 1) { "Decoder checksums disagree" }
        return true
    }

    public fun run(
        kind: String,
        root: Path,
        output: Path,
        environment: Map<String, String> = System.getenv(),
    ) {
        val modes = variants[kind] ?: throw IllegalArgumentException("Unknown Tier E ring benchmark")
        val prefix = if (kind == "decoder") "MAZEWALL_TIER_E_DECODER_" else "MAZEWALL_TIER_E_MAPPED_DRAIN_"

        fun setting(
            key: String,
            default: Int,
            min: Int = 1,
        ): Int =
            (environment["$prefix$key"] ?: default.toString())
                .toIntOrNull()
            ?.takeIf { it >= min } ?: throw IllegalArgumentException("Invalid Tier E $key")
        val forks = setting("FORKS", 5)
        val warmups = setting("WARMUPS", 3, 0)
        val samples = setting("SAMPLES", 10)
        val operations = setting(if (kind == "decoder") "ITERATIONS" else "DRAINS", if (kind == "decoder") 1_000_000 else 100_000)
        val cpu = setting("CPU", 0, 0)
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Ring benchmark requires fresh artifacts" }
        TierEMatrixTerminal.run(output) {
            val logs = Files.createDirectory(output.resolve("child-logs"))
            val results = output.resolve("results.jsonl")
            Files.writeString(
                results,
                buildJsonObject {
                put("kind", "metadata")
                put("forks", forks)
                put("warmups", warmups)
                put("samples", samples)
                put(if (kind == "decoder") "iterations" else "drains", operations)
                put("cpu", cpu.toString())
                put("policy_eligibility", false)
            }.toString() +
                "\n",
            )
            val classpath = root.resolve("profiler/build/install/tier-e-daemon/lib/*").toString()
            for (fork in 1..forks) {
                for (offset in modes.indices) {
                    val mode = modes[(fork - 1 + offset) % modes.size]
                    val log = logs.resolve("$mode-fork-$fork.jsonl")
                    val main = if (kind == "decoder") {
                        "io.mazewall.profiler.tierE.ringbuf.TierERingDecoderMicrobenchmark"
                    } else {
                        "io.mazewall.profiler.tierE.ringbuf.TierERingMappedDrainMicrobenchmark"
                    }
                    val child = ProcessBuilder(
                        "taskset",
                        "-c",
                        cpu.toString(),
                        "java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-Dmazewall.tier-e.${if (kind == "decoder") "decoder" else "mapped-drain"}-microbenchmark.fork=$fork",
                        "-cp",
                        classpath,
                        main,
                        mode,
                        warmups.toString(),
                        samples.toString(),
                        operations.toString(),
                    ).redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start()
                    if (!child.waitFor(Duration.ofMinutes(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        child.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                        child.destroyForcibly()
                        error("Ring benchmark child timed out: $mode")
                    }
                    check(child.exitValue() == 0) { "Ring benchmark child failed: $mode (${child.exitValue()})" }
                    Files.writeString(results, Files.readString(log), APPEND)
                }
            }
            verify(kind, results, forks, samples)
            Files.writeString(
                results,
                """{"kind":"terminal","success":true,"policy_eligibility":false}
""",
                    APPEND,
            )
        }
    }
}
