package io.mazewall.testing.containers

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Owns the daemon-side resources even when the fixture shell is killed. */
public class TierEContainerRun(
    public val endpoint: ContainerEndpoint,
    public val runId: String,
    private val artifacts: Path,
    private val execute: (List<String>, Duration) -> ContainerCommandResult = { command, timeout ->
        runContainerCommand(command, timeout)
    },
) : AutoCloseable {
    init {
        require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))) { "Invalid scoped Tier E run ID" }
    }

    override fun close() {
        val failures = mutableListOf<String>()
        for (suffix in listOf("target", "collector")) {
            val name = "$runId-$suffix"
            val existing = execute(endpoint.command("inspect", name), Duration.ofSeconds(5))
            if (existing.exitCode != 0) {
                if (!missing(existing.output)) failures += "$name inspection failed: ${existing.output}"
                continue
            }
            val logs = execute(endpoint.command("logs", name), Duration.ofSeconds(5))
            if (logs.exitCode == 0) {
                try {
                    Files.createDirectories(artifacts)
                    val output = artifacts.resolve("$suffix.log")
                    if (!Files.exists(output)) Files.writeString(output, logs.output)
                } catch (failure: Exception) {
                    failures += "$name log retention failed: ${failure.message}"
                }
            } else {
                failures += "$name logs failed: ${logs.output}"
            }
            val removed = execute(endpoint.command("rm", "-f", name), Duration.ofSeconds(10))
            if (removed.exitCode != 0 && !missing(removed.output)) failures += "$name removal failed: ${removed.output}"
            val after = execute(endpoint.command("inspect", name), Duration.ofSeconds(5))
            if (after.exitCode == 0) {
                failures += "$name still exists after removal"
            } else if (!missing(after.output)) {
                failures += "$name removal verification failed: ${after.output}"
            }
        }
        if (failures.isNotEmpty()) throw IllegalStateException(failures.joinToString("; "))
    }

    private fun missing(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("no such object") ||
            lower.contains("no such container") ||
            lower.contains("no container with name")
    }
}
