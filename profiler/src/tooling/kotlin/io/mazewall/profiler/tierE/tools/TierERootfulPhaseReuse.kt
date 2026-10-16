package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Admit separate fresh native phase evidence only for this exact rootful source and binary. */
public object TierERootfulPhaseReuse {
    public data class Expected(
        val sourceDigest: String,
        val sourceRevision: String,
        val imageId: String,
        val agentSha256: String,
        val workloadClassSha256: String,
        val compiler: String,
        val java: String,
        val cpuSet: String,
    )

    public fun accept(
        phase: Path,
        output: Path,
        baseline: Path,
        expected: Expected,
    ) {
        require(Files.isDirectory(phase, NOFOLLOW_LINKS) && Files.isDirectory(output, NOFOLLOW_LINKS)) {
            "Phase handoff requires owned artifact directories"
        }
        val names = listOf("terminal.json", "native-identity.json", "phase-full-verdict.txt", "phase-unique-verdict.txt") +
            listOf("full", "unique").flatMap { mode -> (1..3).map { "$mode-$it.log" } }
        require(names.all { Files.isRegularFile(phase.resolve(it), NOFOLLOW_LINKS) }) {
            "Phase handoff lacks complete unsymlinked evidence"
        }
        val terminal = Json.parseToJsonElement(Files.readString(phase.resolve("terminal.json"))).jsonObject
        require(
            terminal["schema_version"]?.jsonPrimitive?.content == "1" &&
            terminal["success"]?.jsonPrimitive?.content == "true" &&
            terminal["reason"]?.jsonPrimitive?.content == "COMPLETE" &&
            terminal["policy_eligibility"]?.jsonPrimitive?.content == "false",
        ) {
            "Native phase terminal is incomplete or policy-eligible"
        }
        val identity = Json.parseToJsonElement(Files.readString(phase.resolve("native-identity.json"))).jsonObject
        val bound = mapOf(
            "source_digest" to expected.sourceDigest,
            "source_revision" to expected.sourceRevision,
            "image_id" to expected.imageId,
            "agent_sha256" to expected.agentSha256,
            "workload_class_sha256" to expected.workloadClassSha256,
            "compiler" to expected.compiler,
            "java" to expected.java,
            "cpu_set" to expected.cpuSet,
        )
        require(
            identity["schema_version"]?.jsonPrimitive?.content == "1" &&
            identity["policy_eligibility"]?.jsonPrimitive?.content == "false" &&
            !identity["run_id"]?.jsonPrimitive?.content.isNullOrBlank() &&
            bound.all { (name, value) -> identity[name]?.jsonPrimitive?.content == value },
        ) {
            "Native phase source, image, binary, class, toolchain or CPUs differ"
        }
        for (mode in listOf("full", "unique")) {
            val logs = (1..3).joinToString("\n") { Files.readString(phase.resolve("$mode-$it.log")) }
            val actual = TierEBenchmarkPhaseGate.comparable(mode, logs, TierEBenchmarkPhaseGate.baselines(baseline, mode))
            val verdict = Json.parseToJsonElement(Files.readString(phase.resolve("phase-$mode-verdict.txt"))).jsonObject
            require(
                verdict["status"]?.jsonPrimitive?.content == "PASS" &&
                verdict["policy_eligibility"]?.jsonPrimitive?.content == "false" &&
                actual.all { (key, value) -> verdict[key]?.jsonPrimitive?.content == value.toString() },
            ) {
                "Retained native phase verdict differs from its raw samples: $mode"
            }
        }
        val replayFiles = names.drop(4)
        require(replayFiles.none { Files.exists(output.resolve(it), NOFOLLOW_LINKS) }) { "Phase replay would overwrite gate evidence" }
        for (name in replayFiles) Files.copy(phase.resolve(name), output.resolve(name))
        TierERootfulPhaseEvidence.verify(output, baseline).getOrThrow()
        val sha = MessageDigest.getInstance("SHA-256")

        fun digest(file: Path): String = sha.digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
        require(replayFiles.all { digest(phase.resolve(it)) == digest(output.resolve(it)) }) {
            "Native phase samples changed during handoff"
        }
        Files.writeString(
            output.resolve("phase-origin.json"),
            buildJsonObject {
            put("schema_version", 1)
            put("run_id", identity.getValue("run_id").jsonPrimitive.content)
            put("source_digest", expected.sourceDigest)
            put("image_id", expected.imageId)
            put("agent_sha256", expected.agentSha256)
            put("terminal_sha256", digest(phase.resolve("terminal.json")))
            put("policy_eligibility", false)
        }.toString() +
            "\n",
        )
    }
}
