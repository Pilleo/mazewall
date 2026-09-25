package io.mazewall.profiler.testing.tierE

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator

/** Owns the daemon-side resources even when the fixture shell is killed. */
public class TierEContainerRun(
    public val endpoint: ContainerEndpoint,
    public val runId: String,
    private val artifacts: Path,
    private val ownedShared: Path? = null,
    private val execute: (List<String>, Duration, Path?) -> ContainerCommandResult = { command, timeout, output ->
        runContainerCommand(command, timeout, outputFile = output)
    },
) : AutoCloseable {
    init {
        require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))) { "Invalid scoped Tier E run ID" }
    }

    /** Best effort only: creation may still complete later. Never export, remove, or retire scratch. */
    public fun stopForRecovery() {
        val interrupted = Thread.interrupted()
        val failures = mutableListOf<String>()
        try {
            for (suffix in listOf("target", "collector")) {
                val name = "$runId-$suffix"
                try {
                    val existing = execute(endpoint.command("inspect", name), Duration.ofSeconds(5), null)
                    if (existing.exitCode != 0 && missing(existing.output)) continue
                    val result = execute(endpoint.command("stop", "-t", "2", name), Duration.ofSeconds(10), null)
                    if (result.exitCode != 0) failures += "$name stop failed: ${result.output}"
                } catch (failure: Exception) {
                    failures += "$name stop failed: ${failure.message}"
                }
            }
            if (failures.isNotEmpty()) error("Unsettled creation; exact containers retained: ${failures.joinToString("; ")}")
        } finally {
            if (Thread.interrupted() || interrupted) Thread.currentThread().interrupt()
        }
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
                    val stopped = invoke(endpoint.command("stop", "-t", "2", name), Duration.ofSeconds(10))
                    if (stopped.exitCode != 0) {
                        failures += "$name stop failed: ${stopped.output}; retained for recovery: $name"
                        continue
                    }
                    val state = invoke(endpoint.command("inspect", "--format", "{{.State.Running}}", name), Duration.ofSeconds(5))
                    if (state.exitCode != 0 || state.output.trim() != "false") {
                        failures += "$name stop unverified: ${state.output}; retained for recovery: $name"
                        continue
                    }
                    if (existing.exitCode != 0) {
                        failures += "$name inspection failed: ${existing.output}; retained for recovery: $name"
                        continue
                    }
                    val output = try {
                        Files.createDirectories(artifacts)
                        val log = artifacts.resolve("$suffix.owner.log")
                        if (Files.exists(log)) Files.createTempFile(artifacts, "$suffix.owner-", ".log") else log
                    } catch (failure: Exception) {
                        failures += "$name log staging failed: ${failure.message}; stopped container retained for recovery: $name"
                        continue
                    }
                    val logs = invoke(endpoint.command("logs", name), Duration.ofSeconds(5), output)
                    if (logs.exitCode != 0) {
                        failures += "$name logs failed: ${logs.output}; stopped container retained for recovery: $name; partial: $output"
                        continue
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
                    failures += "$name cleanup failed: ${failure.message}; retained for recovery: $name"
                    try {
                        val stopped = invoke(endpoint.command("stop", "-t", "2", name), Duration.ofSeconds(10))
                        if (stopped.exitCode != 0 && !missing(stopped.output)) failures += "$name stop attempt failed: ${stopped.output}"
                    } catch (retry: Exception) {
                        failures += "$name stop attempt failed: ${retry.message}"
                    }
                }
            }
            if (failures.isEmpty() && ownedShared != null) {
                val marker = artifacts.resolve("$runId.shared-path")
                try {
                    val shared = ownedShared
                    if (Files.exists(marker, NOFOLLOW_LINKS)) {
                        check(Path.of(Files.readString(marker).trim()).toRealPath() == shared.toRealPath()) {
                            "Run $runId shared marker does not match the allocated directory: $marker"
                        }
                    }
                        val tempRoot = Path.of(System.getenv("TMPDIR") ?: "/tmp").toRealPath()
                        check(
                            !Files.isSymbolicLink(shared) &&
                            Files.isDirectory(shared, NOFOLLOW_LINKS) &&
                            shared.toRealPath().parent == tempRoot &&
                            shared.fileName.toString().startsWith("tier-e-owned-$runId-") &&
                            Files.getOwner(shared) == Files.getOwner(artifacts),
                        ) {
                            "Shared scratch directory is not an owned mktemp child: $shared"
                        }
                        Files.walk(shared).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                    if (Files.exists(marker, NOFOLLOW_LINKS)) Files.delete(marker)
                } catch (failure: Exception) {
                    failures += "Shared fixture scratch cleanup failed: ${failure.message}; recovery path: $marker"
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
