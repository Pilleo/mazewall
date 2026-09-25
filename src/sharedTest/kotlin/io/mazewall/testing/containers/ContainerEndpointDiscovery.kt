package io.mazewall.testing.containers

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

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
            val requested = env["TIER_E_CONTAINER_ENGINE"]?.takeIf { it.isNotBlank() }?.lowercase()
            val error = requested?.takeUnless { it in setOf("docker", "podman") }
            val engine = when (requested) {
                "docker" -> ContainerEngine.DOCKER
                "podman" -> ContainerEngine.PODMAN
                else -> if (env["MAZEWALL_DOCKER_HOST"].isNullOrBlank() &&
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
                env["MAZEWALL_DOCKER_HOST"]?.takeIf { it.isNotBlank() }
                    ?: env["DOCKER_HOST"]?.takeIf { it.isNotBlank() }
                    ?: env["CONTAINER_HOST"]?.takeIf { it.isNotBlank() },
                error?.let { "invalid TIER_E_CONTAINER_ENGINE: $it" },
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
    try {
        val process = try {
            ProcessBuilder(command)
                .apply { environment().putAll(environment) }
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        } catch (error: java.io.IOException) {
            return ContainerCommandResult(-1, error.message.orEmpty())
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        return ContainerCommandResult(-1, "container command timed out; controller termination unconfirmed")
                    }
                }
                return ContainerCommandResult(-1, "container endpoint probe timed out")
            }
        } catch (interrupted: InterruptedException) {
            process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            try {
                process.waitFor(2, TimeUnit.SECONDS)
            } finally {
                Thread.currentThread().interrupt()
            }
            return ContainerCommandResult(-1, "container command interrupted")
        }
        val summary = Files.newBufferedReader(output).use { reader ->
            val prefix = CharArray(4096)
            val read = reader.read(prefix)
            if (read < 0) "" else String(prefix, 0, read)
        }
        return ContainerCommandResult(process.exitValue(), summary)
    } finally {
        if (outputFile == null) Files.deleteIfExists(output)
    }
}
