package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND

/** Predeclared, fail-closed control/noise order; a later failure preserves completed earlier pairs. */
public object TierENodeNoiseQualification {
    public data class Identity(
        val revision: String,
        val sourceFingerprint: String,
        val imageId: String,
        val agentSha256: String,
        val cpuSet: String,
    )

    public fun schedule(
        runs: Int,
        output: Path,
        control: (Path) -> Identity,
        noisy: (Path) -> Identity,
    ) {
        require(runs in 1..5) { "Node-noise qualification must predeclare at most five pairs" }
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Node-noise qualification needs fresh artifacts" }
        TierEMatrixTerminal.run(output) {
            Files.writeString(output.resolve("results.jsonl"), "")
            val pairs = mutableListOf<JsonObject>()
            for (index in 1..runs) {
                val controlPath = output.resolve("control-$index-artifacts")
                val noisyPath = output.resolve("noisy-$index-artifacts")
                val (controlIdentity, noisyIdentity) = if (index % 2 == 1) {
                    control(controlPath) to noisy(noisyPath)
                } else {
                    val measured = noisy(noisyPath)
                    control(controlPath) to measured
                }
                require(controlIdentity == noisyIdentity) { "Noisy/control source, image, binary or CPU identities differ" }
                val pair = buildJsonObject {
                    put("kind", "node_noise_control_pair")
                    put("run", index)
                    put("order", if (index % 2 == 1) "control,noisy" else "noisy,control")
                    put("source_revision", controlIdentity.revision)
                    put("source_fingerprint", controlIdentity.sourceFingerprint)
                    put("image_id", controlIdentity.imageId)
                    put("agent_sha256", controlIdentity.agentSha256)
                    put("cpu_set", controlIdentity.cpuSet)
                    put("policy_eligibility", false)
                }
                Files.writeString(output.resolve("results.jsonl"), "$pair\n", APPEND)
                pairs += pair
            }
            Files.writeString(
                output.resolve("summary.json"),
                buildJsonObject {
                put("kind", "node_noise_control_summary")
                put("pairs", pairs.size)
                put("records", JsonArray(pairs))
                put("noise_overlap_mode", "dictionary_full_stream_collector_continuous")
                put("policy_eligibility", false)
            }.toString() +
                "\n",
            )
        }
    }

    public fun run(
        profile: TierEExperimentProfile,
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        nativePackage: Path,
    ) {
        require(profile.kind == "node-noise" && profile.nodeNoise.runs == 5) { "Decision lane requires five predeclared noise pairs" }
        val settings = profile.trustworthy
        require(
            settings.scope == "tier-e-sustained-collector" &&
            settings.workload == "syscall_dense" &&
            settings.iterations == 10000 &&
            settings.sustainedRate == 1000 &&
            settings.forks == 1 &&
            settings.batches == 1 &&
            settings.warmups == 0 &&
            !settings.calibration &&
            !settings.rawSysEnterAudit &&
            !settings.exactAdmissionAudit,
        ) {
            "Default node-noise qualification requires original 10,000-operation unaudited schedule"
        }
        val cpus = requireNotNull(profile.cpuSet) { "Decision lane requires explicit target CPUs" }
        TierEAllowedCpuVerifier.requireAvailable("$cpus,${profile.nodeNoise.cpu}")
        require(TierETrustworthySourceState.read(root).getValue("benchmark_input_dirty_count").toString() == "0") {
            "Decision lane requires clean benchmark inputs"
        }
        schedule(5, output, control = { artifact ->
            TierETrustworthyRunner.runWithNativePackage(settings, endpoint, root, artifact, profile.image, cpus, nativePackage)
            verifyTarget(artifact)
        }, noisy = { artifact ->
            TierENodeNoiseProbe.runWithNativePackage(
                root,
                artifact,
                endpoint,
                profile.image,
                1,
                profile.nodeNoise.durationSeconds,
                profile.nodeNoise.cpu,
                settings,
                cpus,
                nativePackage,
            )
            verifyNoisy(artifact)
        })
    }

    private fun verifyNoisy(artifact: Path): Identity {
        val terminal = Json.parseToJsonElement(Files.readString(artifact.resolve("terminal.json"))).jsonObject
        require(terminal["success"] == JsonPrimitive(true) && terminal["policy_eligibility"] == JsonPrimitive(false)) {
            "Noisy controller did not complete its first-fork attachment"
        }
        val paired = Files
            .readAllLines(artifact.resolve("results.jsonl"))
            .filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }
        require(paired.size == 1 && paired.single()["kind"]?.jsonPrimitive?.content == "node_noise_pair") {
            "Noisy arm lacks exactly one completed attachment sample"
        }
        val target = artifact.resolve("target-1-artifact")
        val marker = TierENodeNoiseProbe.workloadPid(Files.readString(artifact.resolve("target-1-artifact.attachment-ready.observed")))
        val rows = Files
            .readAllLines(target.resolve("results.jsonl"))
            .filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }
        require(TierENodeNoiseProbe.boundToFirstFullStream(rows, marker)) {
            "Noisy observation lacks the bound first full-stream target"
        }
        return verifyTarget(target)
    }

    /** Independently check both exact 10,000-operation oracles before admitting a pair. */
    public fun verifyTarget(artifact: Path): Identity {
        val terminal = Json.parseToJsonElement(Files.readString(artifact.resolve("terminal.json"))).jsonObject
        require(terminal["success"] == JsonPrimitive(true) && terminal["policy_eligibility"] == JsonPrimitive(false)) {
            "Noisy/control target terminal incomplete"
        }
        TierETrustworthyResultVerifier.verify(artifact.resolve("results.jsonl"))
        val rows = Files
            .readAllLines(artifact.resolve("results.jsonl"))
            .filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }
        val metadata = rows.single { it["kind"]?.jsonPrimitive?.content == "metadata" }
        require(
            metadata["scope"]?.jsonPrimitive?.content == "tier-e-sustained-collector" &&
            metadata["iterations"]?.jsonPrimitive?.intOrNull == 10000 &&
            metadata["forks"]?.jsonPrimitive?.intOrNull == 1,
        ) {
            "Target schedule differs from the declared operation denominator"
        }
        val expectedModes = setOf("dictionary_full_stream_collector_continuous", "dictionary_unique_edge_collector_continuous")
        val forks = rows.filter { it["kind"]?.jsonPrimitive?.content == "fork-output" }
        require(forks.size == 2 && forks.map { it["variant"]?.jsonPrimitive?.content }.toSet() == expectedModes) {
            "Target is missing one required dictionary mode"
        }
        for (fork in forks) {
            require(
                fork["expected_operations"]?.jsonPrimitive?.intOrNull == 10000 &&
                fork["matched_operations"]?.jsonPrimitive?.intOrNull == 10000 &&
                fork["workload_correctness"]?.jsonPrimitive?.content == "PASS" &&
                fork["whole_session_evidence"]?.jsonPrimitive?.content == "COMPLETE" &&
                listOf("missing_operations", "unresolved_operations", "duplicate_operations", "wrong_operations", "unclassified_operations")
                    .all { fork[it]?.jsonPrimitive?.intOrNull == 0 },
            ) {
                "Target operation oracle incomplete for ${fork["variant"]}"
            }
        }
        val manifest = Json.parseToJsonElement(Files.readString(artifact.resolve("manifest.json"))).jsonObject
        val settings = manifest.getValue("configuration").jsonObject
        require(
            manifest["source_benchmark_input_dirty_count"]?.jsonPrimitive?.intOrNull == 0 &&
            manifest["policy_eligibility"] == JsonPrimitive(false) &&
            settings["benchmark_raw_sys_enter_audit"] == JsonPrimitive(false) &&
            settings["benchmark_exact_admission_audit"] == JsonPrimitive(false),
        ) {
            "Target source is dirty, instrumented, or policy eligible"
        }

        fun text(name: String): String = requireNotNull(manifest[name]?.jsonPrimitive?.content) { "Missing target identity: $name" }
        return Identity(text("source_revision"), text("source_fingerprint"), text("image_id"), text("agent_sha256"), text("cpu_set"))
    }
}
