package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator

/** Exact-name container and volume ownership, with retained recoverable source on export failure. */
public class TierETrustworthyContainerOwner(
    private val endpoint: ContainerEndpoint,
    public val runId: String,
    private val output: Path,
    private val verifyExport: (Path) -> Unit = { TierETrustworthyResultVerifier.verify(it.resolve("results.jsonl")) },
    private val execute: (List<String>, Duration, Path?) -> ContainerCommandResult = { command, timeout, file ->
        runContainerCommand(command, timeout, outputFile = file)
    },
) : AutoCloseable {
    init {
        require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}")))
    }

    public val volume: String = "$runId-artifacts"
    public val runner: String = "$runId-runner"
    private var volumeCreated: Boolean = false
    private var runnerCreated: Boolean = false

    public fun ownVolume() {
        volumeCreated = true
    }

    public fun ownRunner() {
        runnerCreated = true
    }

    override fun close(): Unit = finish(true)

    public fun finish(allowRetire: Boolean) {
        val problems = mutableListOf<String>()
        var exported = !volumeCreated || !runnerCreated
        if (runnerCreated) {
            val logs = execute(endpoint.command("logs", runner), Duration.ofSeconds(15), output.resolve("benchmark-container.log"))
            if (logs.exitCode != 0) problems += "runner logs unavailable"
        }
        if (volumeCreated && runnerCreated) {
            val staging = Files.createTempDirectory(output, ".tier-e-export-")
            try {
                val copied = execute(endpoint.command("cp", "$runner:/artifacts/.", "$staging/"), Duration.ofMinutes(2), null)
                check(copied.exitCode == 0) { "artifact copy failed: ${copied.output}" }
                TierETrustedArtifactExport.publish(staging, output)
                verifyExport(output)
                exported = true
            } catch (failure: Exception) {
                problems += failure.message.orEmpty()
            } finally {
                if (exported) Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
        if (!allowRetire) problems += "Benchmark did not complete all required stages"
        if (problems.isEmpty() && exported) {
            if (runnerCreated) {
                val removed = execute(endpoint.command("rm", "-f", runner), Duration.ofSeconds(15), null)
                if (removed.exitCode != 0) problems += "runner removal failed: ${removed.output}"
            }
            if (volumeCreated && problems.isEmpty()) {
                val removed = execute(endpoint.command("volume", "rm", volume), Duration.ofSeconds(15), null)
                if (removed.exitCode != 0) problems += "volume removal failed: ${removed.output}"
            }
        }
        if (problems.isNotEmpty()) {
            if (runnerCreated) {
                val stopped = execute(endpoint.command("stop", "-t", "2", runner), Duration.ofSeconds(15), null)
                if (stopped.exitCode != 0) problems += "runner stop failed: ${stopped.output}"
            }
            Files.writeString(
                output.resolve("recovery.json"),
                buildJsonObject {
                put("schema_version", 1)
                put("run_id", runId)
                put("runner", runner)
                put("volume", volume)
                put("resource_state", "RETAINED_FOR_RECOVERY")
                put("policy_eligibility", false)
            }.toString() +
                "\n",
            )
            error("Tier E benchmark resources retained: ${problems.joinToString("; ")}")
        }
    }
}
