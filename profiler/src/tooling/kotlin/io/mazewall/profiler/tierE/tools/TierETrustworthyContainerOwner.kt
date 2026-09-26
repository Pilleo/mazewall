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
    private var exported: Boolean = false
    public var exportAttempted: Boolean = false
        private set
    private var runnerRemoved: Boolean = false
    private var retired: Boolean = false
    private var retained: Boolean = false

    public fun ownVolume() {
        volumeCreated = true
    }

    public fun ownRunner() {
        runnerCreated = true
    }

    override fun close(): Unit = finish(true)

    /** Export and verify evidence, but keep every engine-owned source available for subsequent checks. */
    public fun export() {
        check(!exportAttempted && !retired && !retained) { "Tier E export already settled" }
        exportAttempted = true
        if (runnerCreated) {
            val logs = execute(endpoint.command("logs", runner), Duration.ofSeconds(15), output.resolve("benchmark-container.log"))
            check(logs.exitCode == 0) { "Tier E runner logs unavailable: ${logs.output}" }
        }
        if (volumeCreated && runnerCreated) {
            val staging = Files.createTempDirectory(output, ".tier-e-export-")
            try {
                val copied = execute(endpoint.command("cp", "$runner:/artifacts/.", "$staging/"), Duration.ofMinutes(2), null)
                check(copied.exitCode == 0) { "artifact copy failed: ${copied.output}" }
                TierETrustedArtifactExport.publish(staging, output)
                verifyExport(output)
                exported = true
            } finally {
                if (exported) Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        } else {
            exported = true
        }
    }

    /** Irreversible retirement is admitted only after all caller-side evidence checks succeed. */
    public fun retire() {
        check(exported && !retired && !retained) { "Tier E resource retirement requires verified export" }
        if (runnerCreated) {
            val removed = execute(endpoint.command("rm", "-f", runner), Duration.ofSeconds(15), null)
            check(removed.exitCode == 0) { "Tier E runner removal failed: ${removed.output}" }
            runnerRemoved = true
        }
        if (volumeCreated) {
            val removed = execute(endpoint.command("volume", "rm", volume), Duration.ofSeconds(15), null)
            check(removed.exitCode == 0) { "Tier E volume removal failed: ${removed.output}" }
        }
        retired = true
    }

    /** Failure retains the owned source and independently records whether the runner could be stopped. */
    public fun retainForRecovery(reason: String) {
        check(!retired) { "Tier E resources already retired" }
        if (retained) return
        val stopped = if (runnerCreated && !runnerRemoved) {
            execute(endpoint.command("stop", "-t", "2", runner), Duration.ofSeconds(15), null)
        } else {
            null
        }
        Files.writeString(
            output.resolve("recovery.json"),
            buildJsonObject {
                put("schema_version", 1)
                put("run_id", runId)
                put("runner", runner)
                put("volume", volume)
                put("runner_retained", runnerCreated && !runnerRemoved)
                put("volume_retained", volumeCreated)
                put("resource_state", if (volumeCreated || runnerCreated && !runnerRemoved) "RETAINED_FOR_RECOVERY" else "NO_LIVE_RESOURCE")
                put("reason", reason)
                put("policy_eligibility", false)
            }.toString() +
                "\n",
        )
        retained = true
        check(stopped == null || stopped.exitCode == 0) { "Tier E runner stop failed: ${stopped?.output}" }
    }

    public fun finish(allowRetire: Boolean) {
        try {
            export()
            if (allowRetire) {
                retire()
            } else {
                retainForRecovery("Benchmark did not complete all required stages")
                error("Tier E benchmark resources retained: incomplete run")
            }
        } catch (primary: Throwable) {
            if (!retired && !retained) {
                try {
                    retainForRecovery(primary.message.orEmpty())
                } catch (cleanup: Throwable) {
                    primary.addSuppressed(cleanup)
                }
            }
            throw primary
        }
    }
}
