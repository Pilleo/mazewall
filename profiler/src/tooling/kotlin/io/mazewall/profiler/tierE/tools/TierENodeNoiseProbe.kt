package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Benchmark-only global node-noise diagnostic; Java procfs-loop values never join historical shell-loop samples. */
public object TierENodeNoiseProbe {
    public fun observedMarker(
        output: Path,
        target: Path,
    ): Path {
        require(target.parent == output && target.fileName.toString().matches(Regex("target-[1-9][0-9]*-artifact"))) {
            "Unexpected node-noise target artifact"
        }
        return output.resolve("${target.fileName}.attachment-ready.observed")
    }

    public fun workloadPid(marker: String): Long =
        Regex("^workload_pid=([1-9][0-9]*)\\n$")
            .matchEntire(marker)
        ?.groupValues
            ?.get(1)
            ?.toLongOrNull() ?: throw IllegalArgumentException("Invalid attached workload identity")

    public fun boundToFirstFullStream(
        rows: List<JsonObject>,
        pid: Long,
    ): Boolean {
        if (pid <= 0) return false
        val selected = rows.filter {
            it["kind"]?.jsonPrimitive?.content == "fork-output" &&
                it["variant"]?.jsonPrimitive?.content?.startsWith("dictionary_full_stream") == true
        }
        return selected.size == 1 &&
            Regex("(?:^|\\s)workload_pid=$pid(?:\\s|$)")
            .containsMatchIn(
                selected
                .single()["output"]
                ?.jsonPrimitive
                ?.content
                .orEmpty(),
            )
    }

    public fun runPairs(
        runs: Int,
        output: Path,
        observe: (String) -> TierENodeNoiseSample,
        attached: (Path) -> Unit,
        completed: (Path) -> Unit = {},
    ) {
        require(runs in 1..20)
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Node-noise probe requires fresh artifacts" }
        TierEMatrixTerminal.run(output) {
        Files.writeString(output.resolve("results.jsonl"), "")
        val results = mutableListOf<kotlinx.serialization.json.JsonObject>()
        for (index in 1..runs) {
            val baseline = observe("baseline-$index")
            val target = output.resolve("target-$index-artifact")
            attached(target)
            val published = observe("attached-$index")
            completed(target)
            val pair = buildJsonObject {
                put("kind", "node_noise_pair")
                put("run", index)
                put("baseline", baseline.json())
                put("global_raw_tracepoint_attached", published.json())
                put("policy_eligibility", false)
            }
            Files.writeString(output.resolve("results.jsonl"), "$pair\n", java.nio.file.StandardOpenOption.APPEND)
            results += pair
        }
        Files.writeString(
            output.resolve("summary.json"),
            buildJsonObject {
            put("kind", "summary")
            put("pairs", results.size)
            put("policy_eligibility", false)
            put("records", JsonArray(results))
            put("measurement_note", "Java procfs loop; do not combine with historical Bash-loop rates")
        }.toString() +
            "\n",
        )
        }
    }

    public fun run(
        repository: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        image: String,
        runs: Int,
        durationSeconds: Int,
        noiseCpu: Int,
        targetSettings: TierETrustworthySettings? = null,
        targetCpus: String? = null,
    ) {
        require(durationSeconds in 1..30 && noiseCpu >= 0)
        val executor = Executors.newSingleThreadExecutor()
        var active: java.util.concurrent.Future<*>? = null
        val observedPids = mutableMapOf<Path, Long>()
        var primary: Throwable? = null
        try {
            runPairs(runs, output, observe = { label ->
                val log = output.resolve("noise-$label.log")
                val classpath = repository.resolve("profiler/build/install/tier-e-daemon/lib/*")
                val command = listOf(
                    "/usr/bin/time",
                    "-a",
                    "-f",
                    "user_seconds=%U system_seconds=%S elapsed_seconds=%e",
                    "-o",
                    log.toString(),
                    "taskset",
                    "-c",
                    noiseCpu.toString(),
                    "java",
                    "-cp",
                    classpath.toString(),
                    "io.mazewall.profiler.tierE.tools.TierENodeNoiseTarget",
                    durationSeconds.toString(),
                )
                val result = runContainerCommand(command, Duration.ofSeconds(durationSeconds.toLong() + 30), outputFile = log)
                check(result.exitCode == 0) { "Node-noise loop failed: ${result.output}" }
                TierENodeNoiseSample.parse(Files.readString(log))
            }, attached = { destination ->
                val settings = targetSettings ?: TierETrustworthySettings.load(
                    System.getenv() +
                    mapOf(
                    "MAZEWALL_TIER_E_RUN_LEVEL" to "custom",
                        "MAZEWALL_TIER_E_CALIBRATION" to "false",
                    "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-sustained-collector",
                    "MAZEWALL_TIER_E_BENCHMARK_WORKLOAD" to "syscall_dense",
                    "MAZEWALL_TIER_E_SUSTAINED_LOAD" to "near_capacity",
                    "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND" to "1000",
                    "MAZEWALL_TIER_E_ITERATIONS" to "10000",
                        "MAZEWALL_TIER_E_WARMUPS" to "0",
                    "MAZEWALL_TIER_E_BATCHES" to "1",
                        "MAZEWALL_TIER_E_FORKS" to "1",
                    "MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS" to "-Dmazewall.tier-e.attachment-ready-file=/artifacts/attachment-ready",
                ),
                )
                val workloadCpus = targetCpus ?: if (targetSettings == null) {
                    System.getenv("MAZEWALL_TIER_E_BENCHMARK_CPUS") ?: "0,1"
                } else {
                    error("Profile-selected node-noise requires explicit target CPUs")
                }
                active = executor.submit {
                    TierETrustworthyRunner.run(
                        settings,
                        endpoint,
                        repository,
                        destination,
                        image,
                        workloadCpus,
                    )
                }
                val manifest = destination.resolve("manifest.json")
                val buildDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20)
                while (!Files.exists(manifest)) {
                    check(System.nanoTime() < buildDeadline && active?.isDone != true) { "Node-noise target failed before bundle admission" }
                    Thread.sleep(100)
                }
                val runId = Json
                    .parseToJsonElement(Files.readString(manifest))
                    .jsonObject
                    .getValue("run_id")
                    .jsonPrimitive.content
                val runner = "$runId-runner"
                val ready = observedMarker(output, destination)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                while (!Files.exists(ready)) {
                    check(System.nanoTime() < deadline && active?.isDone != true) { "Node-noise target never produced attachment-ready evidence" }
                    runContainerCommand(endpoint.command("cp", "$runner:/artifacts/attachment-ready", ready.toString()), Duration.ofSeconds(5))
                    Thread.sleep(100)
                }
                val pid = workloadPid(Files.readString(ready))
                check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    "Attached node-noise workload PID is not alive"
                }
                observedPids[destination] = pid
                // The target stays active while the paired attached sample runs.
            }, completed = { destination ->
                val pid = observedPids.getValue(destination)
                check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    "Attached node-noise workload exited during observation"
                }
                active?.get(30, TimeUnit.MINUTES)
                val rows = Files
                    .readAllLines(destination.resolve("results.jsonl"))
                    .filter(String::isNotBlank)
                    .map { Json.parseToJsonElement(it).jsonObject }
                check(boundToFirstFullStream(rows, pid)) {
                    "Observed attachment marker does not belong to the profiled workload"
                }
            })
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            active?.cancel(true)
            executor.shutdownNow()
            val interrupted = Thread.interrupted()
            try {
                check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Node-noise target did not settle after cancellation" }
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            } finally {
                if (Thread.interrupted() || interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
