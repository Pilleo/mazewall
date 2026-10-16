package io.mazewall.profiler.tierE.tools

import io.mazewall.profiler.tierE.tools.controller.TierENativeFixtureBuild
import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEndpointDiscovery
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.EndpointConfig
import io.mazewall.testing.containers.EndpointSelection
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.security.MessageDigest

/** Explicit Kotlin matrix controller; long workloads are never dependencies of normal builds. */
public object TierECoverageMatrixRunner {
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    public fun run(
        config: TierERunConfiguration,
        root: Path,
        output: Path,
        longMatched: Boolean,
        variants: String,
        hybridOnly: Boolean = false,
    ) {
        config.validate()
        val arms = TierECoverageMatrix.plan(config.measurement.forks, longMatched, variants, hybridOnly)
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E matrix requires fresh artifact directory" }
        val sourceBefore = TierESourceFingerprint.read(root, null)
        Files.writeString(output.resolve("source-state-before.json"), "$sourceBefore\n")
        val selected = ContainerEndpointDiscovery().discover(EndpointConfig(ContainerEngine.valueOf(config.container.engine.uppercase()), config.container.uri))
        val endpoint = (selected as? EndpointSelection.Ready)?.endpoint
            ?: throw IllegalStateException("Tier E matrix requires rootful endpoint: $selected")
        check(endpoint.resolvedUri?.startsWith("unix://") == true) { "Tier E matrix requires verified local endpoint" }
        val image = runContainerCommand(endpoint.command("image", "inspect", config.container.image, "--format", "{{.Id}}"))
        check(image.exitCode == 0 && image.output.trim().startsWith("sha256:")) { "Tier E image identity unavailable: ${image.output}" }
        val native = TierENativeFixtureBuild.prepare(root, config.build.nativeBuildDir)
        val nativeHash = sha(Files.readAllBytes(native.resolve("libmazewall_stack_agent.so")))
        val jars = root.resolve("profiler/build/install/tier-e-daemon/lib")
        val jarLines = Files.list(jars).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".jar") }
            .map { file -> "${sha(Files.readAllBytes(file))}  ${file.toAbsolutePath()}" }
                .sorted()
                .toList()
        }
        check(jarLines.isNotEmpty()) { "Tier E daemon tooling distribution missing" }
        val jarDigest = sha((jarLines.joinToString("\n") + "\n").toByteArray())
        val sourceDigest = sourceBefore.getValue("source_digest").jsonPrimitive.content
        val manifest = buildJsonObject {
            put("schema_version", if (longMatched) 2 else 1)
            put("long_matched", longMatched)
            put("forks", config.measurement.forks)
            put("arms_per_fork", if (longMatched) 7 else arms.size / config.measurement.forks)
            put("declared_operations", config.fixture.operations)
            put("seed_start", 23063)
            put("arm_order", if (longMatched) "baseline first then six variants rotated by (seed % 6)" else "variant-major")
            put("arm_timeout_sec", config.deadlines.armSeconds)
            put("kill_after_sec", 30)
            put("source_digest", sourceDigest)
            put("image_id", image.output.trim())
            put("native_so_sha256", nativeHash)
            put("daemon_jars_sha256", jarDigest)
            put("policy_eligibility", false)
        }
        Files.writeString(output.resolve("run-manifest.json"), "$manifest\n")
        val failures = mutableListOf<String>()
        try {
            for (arm in arms) {
                val artifact = output.resolve("${arm.name}/fork-${arm.fork}")
                Files.createDirectories(artifact)
                val armConfig = config.copy(
                    fixture = config.fixture.copy(coverage = true, emissionMode = arm.emission, recoveryMode = arm.recovery),
                    scenario = config.scenario.copy(
                        markerVersion = arm.marker,
                        stackTransport = arm.transport,
                        faultSyscall = null,
                        negativeArtifact = null,
                        identityCase = null,
                        baseline = arm.baseline,
                        benchmark = false,
                        allowIncomplete = !longMatched && arm.transport == "inline-per-invocation" && config.fixture.operations > 72,
                    ),
                )
                var status = 0
                var timedOut = false
                try {
                    TierESplitFixtureController.run(armConfig, endpoint, root, artifact, native, arm.seed, pinCpus = longMatched)
                } catch (failure: Exception) {
                    status = if (failure.message.orEmpty().contains("timed out", ignoreCase = true)) 124 else 1
                    timedOut = status == 124
                    Files.writeString(artifact.resolve("runner-failure.txt"), failure.stackTraceToString())
                }
                val complete = status == 0 &&
                    if (longMatched) {
                    TierELongMatchedArmStatus.complete(artifact, arm.baseline)
                } else {
                    TierELongMatchedArmStatus.legacySummary(artifact, armConfig.scenario.allowIncomplete)
                }
                if (!complete) failures += "${arm.name}/fork-${arm.fork}"
                if (longMatched) {
                    val meta = buildJsonObject {
                        put("fork", arm.fork)
                        put("arm", arm.name)
                        put("status", status)
                        put("timedOut", timedOut)
                        put("seed", arm.seed)
                        put("declaredOperations", config.fixture.operations)
                        put("sourceDigest", sourceDigest)
                        put("imageId", image.output.trim())
                        put("nativeSoSha256", nativeHash)
                        put("daemonJarsSha256", jarDigest)
                    }
                    Files.writeString(
                        output.resolve("fork-results.jsonl"),
                        "${TierELongMatchedRowBuilder.build(meta, artifact)}\n",
                        CREATE,
                        APPEND,
                    )
                    Files.writeString(
                        artifact.resolve("arm-result.json"),
                        buildJsonObject {
                        put("schema_version", 1)
                        put("fork", arm.fork)
                        put("arm", arm.name)
                        put("status", if (complete) "COMPLETE" else "INCOMPLETE")
                        put("fixture_exit", status)
                        put("timed_out", timedOut)
                        put("seed", arm.seed)
                        put("policy_eligibility", false)
                    }.toString() +
                        "\n",
                    )
                }
            }
            val after = TierESourceFingerprint.read(root, sourceBefore)
            Files.writeString(output.resolve("source-state-after.json"), "$after\n")
            if (after["unchanged"] != JsonPrimitive(true)) failures += "source_state_drift"
            val currentImage = runContainerCommand(endpoint.command("image", "inspect", config.container.image, "--format", "{{.Id}}"))
            val currentNative = sha(Files.readAllBytes(native.resolve("libmazewall_stack_agent.so")))
            val currentJars = Files.list(jars).use { paths ->
                paths
                    .filter { it.fileName.toString().endsWith(".jar") }
                .map { file -> "${sha(Files.readAllBytes(file))}  ${file.toAbsolutePath()}" }
                    .sorted()
                    .toList()
            }
            val stable = currentImage.exitCode == 0 &&
                currentImage.output.trim() == image.output.trim() &&
                currentNative == nativeHash &&
                sha((currentJars.joinToString("\n") + "\n").toByteArray()) == jarDigest
            if (!stable) failures += "binary_identity_drift"
            Files.writeString(
                output.resolve("terminal.json"),
                buildJsonObject {
                put("schema_version", if (longMatched) 2 else 1)
                put("success", failures.isEmpty())
                put("forks", config.measurement.forks)
                put("arms", arms.size)
                put("source_state_unchanged", after["unchanged"] == JsonPrimitive(true))
                put("binaries_unchanged", stable)
                put("incomplete_arms", kotlinx.serialization.json.JsonArray(failures.map(::JsonPrimitive)))
                put("policy_eligibility", false)
            }.toString() +
                "\n",
            )
            check(failures.isEmpty()) { "Incomplete Tier E matrix: ${failures.joinToString()}" }
        } catch (failure: Exception) {
            if (!Files.exists(output.resolve("terminal.json"))) {
                Files.writeString(
                    output.resolve("terminal.json"),
                    buildJsonObject {
                    put("schema_version", if (longMatched) 2 else 1)
                    put("success", false)
                    put("policy_eligibility", false)
                    put("reason", failure.message.orEmpty())
                }.toString() +
                    "\n",
                )
            }
            throw failure
        }
    }
}
