package io.mazewall.profiler.tierE.tools

import io.mazewall.profiler.tierE.tools.controller.TierENativeFixtureBuild
import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.containers.ContainerEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Registered-artifact admission matrix. Every case must stay incomplete and policy-ineligible. */
public object TierENegativeMatrix {
    public val scenarios: List<String> = listOf("missing", "malformed", "symlink", "unclosed", "session-mismatch")

    public fun run(
        config: TierERunConfiguration,
        endpoint: ContainerEndpoint,
        root: Path,
        output: Path,
    ) {
        config.validate()
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E negative matrix requires a fresh directory" }
        val native = TierENativeFixtureBuild.prepare(root, config.build.nativeBuildDir)
        for (scenario in scenarios) {
            val artifact = output.resolve(scenario)
            Files.createDirectories(artifact)
            val arm = config.copy(
                fixture = config.fixture.copy(coverage = false),
                scenario = config.scenario.copy(
                    negativeArtifact = scenario,
                    faultSyscall = null,
                    identityCase = null,
                    baseline = false,
                    benchmark = false,
                ),
            )
            TierESplitFixtureController.run(arm, endpoint, root, artifact, native)
            val terminal = Json.parseToJsonElement(Files.readString(artifact.resolve("terminal-report.json"))).jsonObject
            check(terminal["evidence_integrity"]?.jsonPrimitive?.content == "INCOMPLETE") { "$scenario terminal was not incomplete" }
            check(terminal["policy_eligibility"] == JsonPrimitive(false)) { "$scenario was policy eligible" }
            check((terminal["failed_dimensions"] as? JsonArray)?.isNotEmpty() == true) { "$scenario had no failed dimensions" }
        }
        Files.writeString(
            output.resolve("terminal.json"),
            buildJsonObject {
                put("schema_version", 1)
                put("success", true)
                put("scenarios", scenarios.size)
                put("policy_eligibility", false)
            }.toString() +
                "\n",
        )
    }
}
