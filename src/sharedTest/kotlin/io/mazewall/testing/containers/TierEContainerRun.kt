package io.mazewall.testing.containers

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Owns the daemon-side resources even when the fixture shell is killed. */
public class TierEContainerRun(
    public val endpoint: ContainerEndpoint,
    public val runId: String,
    private val artifacts: Path,
    private val execute: (List<String>, Duration, Path?) -> ContainerCommandResult = { command, timeout, output ->
        runContainerCommand(command, timeout, outputFile = output)
    },
) : AutoCloseable {
    init {
        require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))) { "Invalid scoped Tier E run ID" }
    }

    override fun close() {
        var interrupted = Thread.interrupted()
        val failures = mutableListOf<String>()

        fun invoke(
            command: List<String>,
            timeout: Duration,
            output: Path? = null,
        ): ContainerCommandResult {
            interrupted = Thread.interrupted() || interrupted
            val result = execute(command, timeout, output)
            if (result.exitCode == -1 && result.output.contains("interrupted")) {
                interrupted = Thread.interrupted() || interrupted
                return execute(command, timeout, output)
            }
            return result
        }
        try {
            for (suffix in listOf("target", "collector")) {
                val name = "$runId-$suffix"
                try {
                    val existing = invoke(endpoint.command("inspect", name), Duration.ofSeconds(5))
                    if (existing.exitCode != 0 && missing(existing.output)) continue
                    if (existing.exitCode != 0) failures += "$name inspection failed: ${existing.output}"
                    if (existing.exitCode == 0) {
                        try {
                            Files.createDirectories(artifacts)
                            val log = artifacts.resolve("$suffix.owner.log")
                            val output = if (Files.exists(log)) Files.createTempFile(artifacts, "$suffix.owner-", ".log") else log
                            val logs = invoke(endpoint.command("logs", name), Duration.ofSeconds(5), output)
                            if (logs.exitCode != 0) failures += "$name logs failed: ${logs.output}; retained: $output"
                        } catch (failure: Exception) {
                            failures += "$name log retention failed: ${failure.message}"
                        }
                    }
                    val removed = invoke(endpoint.command("rm", "-f", name), Duration.ofSeconds(10))
                    if (removed.exitCode != 0 && !missing(removed.output)) failures += "$name removal failed: ${removed.output}"
                    val after = invoke(endpoint.command("inspect", name), Duration.ofSeconds(5))
                    if (after.exitCode == 0) {
                        failures += "$name still exists after removal"
                    } else if (!missing(after.output)) {
                        failures += "$name removal verification failed: ${after.output}"
                    }
                } catch (failure: Exception) {
                    failures += "$name cleanup failed: ${failure.message}"
                    try {
                        val removed = invoke(endpoint.command("rm", "-f", name), Duration.ofSeconds(10))
                        if (removed.exitCode != 0 && !missing(removed.output)) failures += "$name removal failed: ${removed.output}"
                    } catch (retry: Exception) {
                        failures += "$name removal attempt failed: ${retry.message}"
                    }
                }
            }
            if (failures.isNotEmpty()) throw IllegalStateException(failures.joinToString("; "))
        } finally {
            if (Thread.interrupted() || interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun missing(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("no such object") ||
            lower.contains("no such container") ||
            lower.contains("no container with name")
    }
}
