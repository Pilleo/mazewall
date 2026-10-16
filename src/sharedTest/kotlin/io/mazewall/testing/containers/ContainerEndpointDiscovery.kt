package io.mazewall.testing.containers

import io.mazewall.platform.fd.CleanupFailures
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val CONTAINER_POLL_MILLIS = 10L

public enum class ContainerEngine { DOCKER, PODMAN }

public data class ContainerEndpoint(
    val engine: ContainerEngine,
    val uri: String?,
    /** Resolved server location; [uri] retains the CLI's selected context. */
    val resolvedUri: String? = uri,
) {
    public fun command(vararg arguments: String): List<String> =
        listOf(engine.name.lowercase()) +
            (uri?.let { listOf(if (engine == ContainerEngine.DOCKER) "--host" else "--url", it) } ?: emptyList()) +
            arguments
}

public data class EndpointConfig(
    val engine: ContainerEngine? = null,
    val uri: String? = null,
    val configurationError: String? = null,
) {
    public companion object {
        public fun fromEnvironment(env: Map<String, String> = System.getenv()): EndpointConfig {
            val requested = env["MAZEWALL_CONTAINER_ENGINE"]?.takeIf { it.isNotBlank() }?.lowercase()
            val error = requested?.takeUnless { it in setOf("docker", "podman") }
            val engine = when (requested) {
                "docker" -> ContainerEngine.DOCKER
                "podman" -> ContainerEngine.PODMAN
                else -> if (env["MAZEWALL_CONTAINER_URI"].isNullOrBlank() &&
                    env["DOCKER_HOST"].isNullOrBlank() &&
                    !env["CONTAINER_HOST"].isNullOrBlank()
                ) {
                    ContainerEngine.PODMAN
                } else {
                    null
                }
            }
            return EndpointConfig(
                engine,
                env["MAZEWALL_CONTAINER_URI"]?.takeIf { it.isNotBlank() }
                    ?: env["DOCKER_HOST"]?.takeIf { it.isNotBlank() }
                    ?: env["CONTAINER_HOST"]?.takeIf { it.isNotBlank() },
                error?.let { "invalid MAZEWALL_CONTAINER_ENGINE: $it" },
            )
        }
    }
}

public sealed interface EndpointProbeResult {
    public data object Rootful : EndpointProbeResult

    public data object Rootless : EndpointProbeResult

    public data class Unavailable(
        val reason: String,
    ) : EndpointProbeResult
}

public sealed interface EndpointSelection {
    public data class Ready(
        val endpoint: ContainerEndpoint,
    ) : EndpointSelection

    public data class Skipped(
        val reason: String,
    ) : EndpointSelection
}

public fun interface EndpointProbe {
    public fun probe(endpoint: ContainerEndpoint): EndpointProbeResult
}

public class ContainerEndpointDiscovery(
    private val resolveDefault: (ContainerEndpoint) -> String? = ::resolveSelectedConnection,
    private val probe: EndpointProbe = CliEndpointProbe(),
) {
    public fun discover(config: EndpointConfig = EndpointConfig.fromEnvironment()): EndpointSelection {
        if (config.configurationError != null) return EndpointSelection.Skipped(config.configurationError)
        val candidates = if (config.engine != null || config.uri != null) {
            listOf(ContainerEndpoint(config.engine ?: ContainerEngine.DOCKER, config.uri))
        } else {
            listOf(ContainerEndpoint(ContainerEngine.DOCKER, null), ContainerEndpoint(ContainerEngine.PODMAN, null))
        }
        val reasons = mutableListOf<String>()
        for (candidate in candidates) {
            when (val result = probe.probe(candidate)) {
                EndpointProbeResult.Rootful ->
                    return EndpointSelection.Ready(candidate.copy(resolvedUri = candidate.uri ?: resolveDefault(candidate)))
                EndpointProbeResult.Rootless -> reasons += "${candidate.engine} is rootless"
                is EndpointProbeResult.Unavailable -> reasons += "${candidate.engine}: ${result.reason}"
            }
        }
        return EndpointSelection.Skipped("No usable rootful container endpoint: ${reasons.joinToString("; ")}")
    }
}

private fun resolveSelectedConnection(endpoint: ContainerEndpoint): String? {
    if (endpoint.uri != null) return endpoint.uri
    val command = when (endpoint.engine) {
        ContainerEngine.DOCKER -> endpoint.command("context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
        ContainerEngine.PODMAN -> endpoint.command("system", "connection", "list", "--format", "{{.Default}} {{.URI}}")
    }
    val result = runContainerCommand(command, Duration.ofSeconds(5))
    if (result.exitCode != 0) return null
    val resolved = when (endpoint.engine) {
        ContainerEngine.DOCKER -> result.output.trim()
        ContainerEngine.PODMAN ->
            result.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("true ") }
            ?.removePrefix("true ") ?: return null
    }
    return resolved.takeIf { it.startsWith("unix://") || it.startsWith("ssh://") || it.startsWith("tcp://") }
}

public class CliEndpointProbe(
    private val execute: (List<String>, Duration) -> ContainerCommandResult = ::runContainerCommand,
) : EndpointProbe {
    override fun probe(endpoint: ContainerEndpoint): EndpointProbeResult {
        val format = if (endpoint.engine == ContainerEngine.DOCKER) "{{json .SecurityOptions}}" else "{{.Host.Security.Rootless}}"
        val result = execute(endpoint.command("info", "--format", format), Duration.ofSeconds(5))
        if (result.exitCode != 0) {
            val detail = result.output.trim().takeUnless { it.isEmpty() || it == "null" }
            return EndpointProbeResult.Unavailable("container server unreachable (exit ${result.exitCode}${detail?.let { ": ${it.take(256)}" } ?: ""})")
        }
        val output = result.output.trim().lowercase()
        return when (endpoint.engine) {
            ContainerEngine.DOCKER -> when {
                !output.matches(Regex("""\["name=[^"\\]*"(,"name=[^"\\]*")*]""")) && output != "[]" ->
                    EndpointProbeResult.Unavailable("unrecognized Docker security options")
                output.contains("rootless") -> EndpointProbeResult.Rootless
                else -> EndpointProbeResult.Rootful
            }
            ContainerEngine.PODMAN -> when (output) {
                "true" -> EndpointProbeResult.Rootless
                "false" -> EndpointProbeResult.Rootful
                else -> EndpointProbeResult.Unavailable("unrecognized Podman rootless state")
            }
        }
    }
}

public data class ContainerCommandResult(
    val exitCode: Int,
    val output: String,
)

public fun runContainerCommand(
    command: List<String>,
    timeout: Duration = Duration.ofSeconds(5),
    environment: Map<String, String> = emptyMap(),
    outputFile: Path? = null,
): ContainerCommandResult {
    val output = outputFile ?: Files.createTempFile("mazewall-container-probe-", ".log")
    var owner: ContainerCommandOwner? = null
    var result: ContainerCommandResult? = null
    var failure: Throwable? = null
    var interrupted = Thread.interrupted()
    try {
        val execution = runCatching {
            if (interrupted) throw InterruptedException("Container command owner was already interrupted")
            val acquired = ContainerCommandOwner()
            owner = acquired
            acquired.execute(command, environment, output, timeout)
        }
        result = execution.getOrNull()
        execution.exceptionOrNull()?.let { caught ->
            failure = caught
            if (caught is InterruptedException) interrupted = true
        }
    } finally {
        try {
            interrupted = Thread.interrupted() || interrupted
            val cleanup = runCatching { owner?.terminate() }
            cleanup.exceptionOrNull()?.let { failure = CleanupFailures.merge(failure, it) }
            interrupted = Thread.interrupted() || interrupted || owner?.interrupted == true
            val outputCleanup = runCatching { if (outputFile == null) Files.deleteIfExists(output) }
            outputCleanup.exceptionOrNull()?.let { failure = CleanupFailures.merge(failure, it) }
        } finally {
            interrupted = Thread.interrupted() || interrupted
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    failure?.let { throw it }
    return checkNotNull(result)
}

/** Only handles observed from the acquired process tree are cleanup authority. */
private class ContainerCommandOwner {
    private var acquired: Process? = null
    private val process: Process get() = checkNotNull(acquired)
    private val observed = linkedMapOf<Long, ProcessHandle>()
    private var failure: Throwable? = null
    var interrupted = false
        private set

    fun execute(
        command: List<String>,
        environment: Map<String, String>,
        output: Path,
        timeout: Duration,
    ): ContainerCommandResult {
        val started = runCatching {
            ProcessBuilder(command)
                .apply { environment().putAll(environment) }
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
                .also { acquired = it }
        }
        val startFailure = started.exceptionOrNull()
        // ProcessBuilder's outer message includes untrusted executable/path text.
        // Redirect-open FileNotFoundException also appears as a nested cause and
        // includes untrusted file text. Require the exact native IOException type
        // and observed errno format; unknown causes/formats fail closed.
        val launchFailure = (startFailure as? java.io.IOException)?.cause as? java.io.IOException
        if (launchFailure != null &&
            launchFailure.javaClass == java.io.IOException::class.java &&
            launchFailure.message.orEmpty().matches(Regex("(?:error=2,.*|Exec failed, error: 2 \\(.*)"))
        ) {
            return ContainerCommandResult(-1, startFailure?.message.orEmpty())
        }
        val process = started.getOrThrow()
        if (!waitFor(timeout)) return ContainerCommandResult(-1, "container endpoint probe timed out")
        val summary = Files.newBufferedReader(output).use { reader ->
            val prefix = CharArray(4096)
            val read = reader.read(prefix)
            if (read < 0) "" else String(prefix, 0, read)
        }
        return ContainerCommandResult(process.exitValue(), summary)
    }

    private fun observe() {
        process.descendants().use { handles -> handles.forEach { observed[it.pid()] = it } }
        observed.values.toList().forEach { child ->
            child.descendants().use { handles -> handles.forEach { observed[it.pid()] = it } }
        }
    }

    fun waitFor(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        do {
            observe()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return !process.isAlive
            if (process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(CONTAINER_POLL_MILLIS)), TimeUnit.NANOSECONDS)) {
                observe()
                return true
            }
        } while (true)
    }

    private fun attempt(action: () -> Unit) {
        val outcome = runCatching(action)
        outcome.exceptionOrNull()?.let { caught ->
            if (caught is InterruptedException) interrupted = true
            failure = CleanupFailures.merge(failure, caught)
        }
        interrupted = Thread.interrupted() || interrupted
    }

    private fun await(
        deadline: Long,
        includeParent: Boolean,
    ): Boolean {
        while ((includeParent && process.isAlive) || observed.values.any(ProcessHandle::isAlive)) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            attempt { TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(CONTAINER_POLL_MILLIS))) }
        }
        return true
    }

    fun terminate() {
        if (acquired == null) return
        interrupted = Thread.interrupted() || interrupted
        attempt { observe() }
        val gracefulDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        observed.values
            .toList()
            .asReversed()
            .forEach { child -> attempt { child.destroy() } }
        // Let the still-owned parent reap children before retiring it. Both waits
        // share the existing two-second graceful budget, not two new budgets.
        attempt { await(gracefulDeadline, includeParent = false) }
        attempt { process.destroy() }
        var reaped = false
        attempt { reaped = await(gracefulDeadline, includeParent = true) }
        if (!reaped) {
            attempt { observe() }
            val forceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            observed.values
                .toList()
                .asReversed()
                .forEach { child -> attempt { child.destroyForcibly() } }
            attempt { await(forceDeadline, includeParent = false) }
            attempt { process.destroyForcibly() }
            attempt { reaped = await(forceDeadline, includeParent = true) }
            if (!reaped) {
                attempt { error("Container command cleanup could not confirm owned process tree termination") }
            }
        }
        failure?.let { throw it }
    }
}
