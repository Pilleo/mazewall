package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** Local-rootful benchmark controller; native build and measured execution use separate containers. */
public object TierETrustworthyRunner {
    public fun run(
        settings: TierETrustworthySettings,
        endpoint: ContainerEndpoint,
        root: Path,
        output: Path,
        image: String,
        requestedCpus: String? = null,
    ) {
        require(endpoint.engine == ContainerEngine.DOCKER && endpoint.resolvedUri?.startsWith("unix://") == true) {
            "Tier E trustworthy benchmark requires verified local rootful Docker"
        }
        Files.createDirectories(output)
        require(Files.isDirectory(root) && Files.getAttribute(root, "unix:uid") == Files.getAttribute(output, "unix:uid")) {
            "Tier E checkout and artifacts must be caller-owned"
        }
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E benchmark requires fresh artifacts" }
        val repository = root.toRealPath()
        val artifacts = output.toRealPath()
        val runId = "tier-e-trustworthy-${UUID.randomUUID()}"
        val builderName = "$runId-builder"
        val owner = TierETrustworthyContainerOwner(endpoint, runId, artifacts)

        fun command(
            vararg arguments: String,
            timeout: Duration = Duration.ofSeconds(30),
            log: Path? = null,
        ): String {
            val result = runContainerCommand(endpoint.command(*arguments), timeout, outputFile = log)
            check(result.exitCode == 0) { "Tier E Docker command failed: ${arguments.take(3)} (${result.exitCode}): ${result.output}" }
            return result.output.trim()
        }
        TierEMatrixTerminal.run(artifacts) {
            val sourceBefore = TierESourceFingerprint.read(repository, null)
            val sourceState = TierETrustworthySourceState.read(repository)
            Files.writeString(artifacts.resolve("source-state-before.json"), "$sourceBefore\n")
            val cpuPair = if (requestedCpus != null) {
                requestedCpus.split(',')
            } else {
                val topology = runContainerCommand(listOf("lscpu", "-p=CPU,CORE,SOCKET"), Duration.ofSeconds(15)).output
                topology
                    .lineSequence()
                    .filter { !it.startsWith('#') && it.isNotBlank() }
                    .map { it.split(',') }
                    .filter { it.size == 3 }
                    .distinctBy { "${it[1]},${it[2]}" }
                    .take(2)
                    .map { it[0] }
                    .toList()
            }
            require(cpuPair.size == 2 && cpuPair.all { it.toIntOrNull()?.let { cpu -> cpu >= 0 } == true } && cpuPair[0] != cpuPair[1]) {
                "Tier E benchmark needs two distinct physical CPUs"
            }
            val (workloadCpu, collectorCpu) = cpuPair
            val cpuSet = cpuPair.joinToString(",")
            val imageId = command("image", "inspect", image, "--format", "{{.Id}}")
            require(imageId.startsWith("sha256:")) { "Tier E image identity unavailable" }
            val uid = Files.getAttribute(repository, "unix:uid") as Int
            val gid = Files.getAttribute(repository, "unix:gid") as Int
            val cache = Files.createDirectories(repository.resolve("build/tier-e-benchmark-cache"))
            require(Files.getAttribute(cache, "unix:uid") == uid) { "Benchmark cache owner mismatch" }
            val bundle = artifacts.resolve("bundle")
            var builderCreated = false
            try {
                command(
                    "create",
                    "--name",
                    builderName,
                    "--privileged",
                    "--pid=host",
                    "--userns=host",
                    "--cpuset-cpus=$cpuSet",
                    "--security-opt",
                    "seccomp=unconfined",
                    "--user",
                    "$uid:$gid",
                    "-e",
                    "MAZEWALL_TIER_E_REPOSITORY_DIR=$repository",
                    "-e",
                    "MAZEWALL_TIER_E_BUILD_CACHE_ROOT=/benchmark-cache",
                    "-e",
                    "MAZEWALL_TIER_E_NATIVE_BUILD_TYPE=${settings.nativeBuildType}",
                    "-v",
                    "$repository:$repository",
                    "-v",
                    "$artifacts:/artifacts",
                    "-v",
                    "$cache:/benchmark-cache",
                    "-w",
                    repository.toString(),
                    image,
                    "bash",
                    "scripts/build_tier_e_benchmark_bundle.sh",
                    "/artifacts/bundle",
                    (System.getProperty("os.arch") in setOf("amd64", "x86_64")).toString(),
                )
                builderCreated = true
                command("start", "-a", builderName, timeout = Duration.ofMinutes(20), log = artifacts.resolve("build.log"))
                check(command("inspect", "-f", "{{.State.ExitCode}}", builderName) == "0") { "Tier E bundle build failed" }
            } finally {
                val removed = runContainerCommand(endpoint.command("rm", "-f", builderName), Duration.ofSeconds(15))
                if (builderCreated) check(removed.exitCode == 0) { "Tier E builder container retained: $builderName" }
            }
            TierEBenchmarkBundleManifest.verify(bundle)
            if (settings.verifyBuild) {
                val verification = "$runId-build-check"
                var created = false
                try {
                    command(
                        "create",
                        "--name",
                        verification,
                        "--userns=host",
                        "--user",
                        "$uid:$gid",
                        "-v",
                        "$repository:$repository",
                        "-v",
                        "$cache:/benchmark-cache",
                        "-w",
                        repository.toString(),
                        "-e",
                        "GRADLE_USER_HOME=/benchmark-cache/gradle-home",
                        image,
                        repository.resolve("gradlew").toString(),
                        "--no-daemon",
                        ":profiler:test",
                        "--tests",
                        "io.mazewall.profiler.tierE.stress.TierECommonFileWorkloadTest",
                    )
                    created = true
                    command("start", "-a", verification, timeout = Duration.ofMinutes(10), log = artifacts.resolve("build-verification.log"))
                    check(command("inspect", "-f", "{{.State.ExitCode}}", verification) == "0") { "Tier E build verification failed" }
                } finally {
                    val removed = runContainerCommand(endpoint.command("rm", "-f", verification), Duration.ofSeconds(15))
                    if (created) check(removed.exitCode == 0) { "Tier E verification container retained: $verification" }
                }
            }
            val sourceFingerprint = Json
                .parseToJsonElement(Files.readString(bundle.resolve("manifest.json")))
                .jsonObject
                .getValue("input_fingerprint")
                .jsonPrimitive.content
            require(sourceFingerprint.matches(Regex("[a-f0-9]{64}"))) { "Bundle lacks a valid input fingerprint" }
            val sha = MessageDigest.getInstance("SHA-256")

            fun hash(file: Path) = sha.digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
            val revisionResult = runContainerCommand(listOf("git", "-C", repository.toString(), "rev-parse", "HEAD"))
            check(revisionResult.exitCode == 0) { "Tier E source revision unavailable" }
            val revision = revisionResult.output.trim()
            Files.writeString(
                artifacts.resolve("manifest.json"),
                buildJsonObject {
                put("schema_version", 2)
                put("run_id", runId)
                put("source_revision", revision)
                put("source_dirty_count", sourceState.getValue("all_dirty_count"))
                put("source_benchmark_input_dirty_count", sourceState.getValue("benchmark_input_dirty_count"))
                put("source_fingerprint", sourceFingerprint)
                put("source_digest", sourceBefore.getValue("source_digest"))
                put("image", image)
                put("image_id", imageId)
                put("agent_sha256", hash(bundle.resolve("libmazewall_stack_agent.so")))
                put("bundle_manifest_sha256", hash(bundle.resolve("manifest.json")))
                put("cpu_set", cpuSet)
                put("workload_cpu", workloadCpu)
                put("collector_cpu", collectorCpu)
                put("bundle", "bundle")
                put("results", "results.jsonl")
                put("benchmark_log", "benchmark.log")
                put("configuration", settings.resolvedJson())
                put("policy_eligibility", false)
            }.toString() +
                "\n",
            )
            var failure: Throwable? = null
            try {
                check(runContainerCommand(endpoint.command("volume", "inspect", owner.volume)).exitCode != 0) {
                    "Refusing to reuse Tier E artifact volume"
                }
                command("volume", "create", owner.volume)
                owner.ownVolume()
                command(
                    "run",
                    "--rm",
                    "--name",
                    "$runId-chown",
                    "-v",
                    "${owner.volume}:/artifacts",
                    "--entrypoint",
                    "chown",
                    image,
                    "$uid:$gid",
                    "/artifacts",
                )
                val provenanceEnv = mapOf(
                    "MAZEWALL_BENCHMARK_SOURCE_REVISION" to revision,
                    "MAZEWALL_BENCHMARK_SOURCE_DIRTY" to sourceState.getValue("all_dirty_count").toString(),
                    "MAZEWALL_BENCHMARK_IMAGE_ID" to imageId,
                )
                val guestCommand = arrayOf(
                    "create",
                    "--name",
                    owner.runner,
                    "--privileged",
                    "--pid=host",
                    "--userns=host",
                    "--cpuset-cpus=$cpuSet",
                    "--security-opt",
                    "seccomp=unconfined",
                    "-e",
                    "WORKLOAD_CPU=$workloadCpu",
                    "-e",
                    "COLLECTOR_CPU=$collectorCpu",
                ) +
                    (settings.guestEnvironment() + provenanceEnv).flatMap { (key, value) -> listOf("-e", "$key=$value") }.toTypedArray() +
                    arrayOf(
                        "-v",
                        "$bundle:/bundle:ro",
                        "-v",
                        "${owner.volume}:/artifacts",
                        "-w",
                        "/tmp",
                        image,
                        "java",
                        "-cp",
                        "/bundle/runtime/*",
                        "io.mazewall.profiler.tierE.tools.TierETrustworthyGuestMainKt",
                    )
                command(*guestCommand)
                owner.ownRunner()
                command("start", "-a", owner.runner, timeout = Duration.ofMinutes(120))
                check(command("inspect", "-f", "{{.State.ExitCode}}", owner.runner) == "0") { "Tier E guest benchmark failed" }
                if (settings.perfRecord || settings.allocationRecord) {
                    val analysis = "$runId-analysis"
                    var created = false
                    try {
                        val privilege = if (settings.allocationRecord) arrayOf("--privileged", "--security-opt", "seccomp=unconfined") else emptyArray()
                        command(
                            *(
                                arrayOf("create", "--name", analysis) +
                            privilege +
                            arrayOf(
                            "-v",
                                "$bundle:/bundle:ro",
                                "-v",
                                "${owner.volume}:/artifacts",
                                image,
                            "java",
                                "-cp",
                                "/bundle/runtime/*",
                                "io.mazewall.profiler.tierE.tools.TierEPerfGuestMain",
                            if (settings.perfRecord) "perf" else "allocation",
                        )
                            ),
                        )
                        created = true
                        command("start", "-a", analysis, timeout = Duration.ofMinutes(6))
                        check(command("inspect", "-f", "{{.State.ExitCode}}", analysis) == "0") { "Tier E perf analysis failed" }
                    } finally {
                        val removed = runContainerCommand(endpoint.command("rm", "-f", analysis), Duration.ofSeconds(15))
                        if (created) check(removed.exitCode == 0) { "Tier E analysis container retained: $analysis" }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                try {
                    owner.finish(failure == null)
                } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            if (failure != null) throw failure
            if (settings.perfRecord) TierETrustworthyPerfEvidence.write(artifacts, settings.perfJitMap)
            if (settings.allocationRecord) TierETrustworthyAllocationEvidence.write(artifacts)
            val metadata = Files
                .readAllLines(artifacts.resolve("results.jsonl"))
                .map { Json.parseToJsonElement(it).jsonObject }
                .firstOrNull { it["kind"]?.jsonPrimitive?.content == "metadata" }
                ?: error("Benchmark metadata record is missing")
            val manifest = Json.parseToJsonElement(Files.readString(artifacts.resolve("manifest.json"))).jsonObject
            Files.writeString(
                artifacts.resolve("manifest.json"),
                buildJsonObject {
                for ((key, value) in manifest) put(key, value)
                put("selected_iterations", metadata["iterations"] ?: error("Benchmark selected iterations missing"))
                put("calibration_target", metadata["calibration_target"] ?: error("Benchmark calibration target missing"))
            }.toString() +
                "\n",
            )
            val after = TierESourceFingerprint.read(repository, sourceBefore)
            Files.writeString(artifacts.resolve("source-state-after.json"), "$after\n")
            TierEBenchmarkBundleManifest.verify(bundle, sourceFingerprint)
            check(after["unchanged"]?.toString() == "true" && command("image", "inspect", image, "--format", "{{.Id}}") == imageId) {
                "Tier E benchmark inputs changed during measurement"
            }
        }
    }
}
