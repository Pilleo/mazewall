package io.mazewall.testing.containers

import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

public enum class ContainerEngine { DOCKER, PODMAN }

public data class ContainerEndpoint(
    val engine: ContainerEngine,
    val uri: String?,
) {
    public fun command(vararg arguments: String): List<String> =
        listOf(engine.name.lowercase()) +
            (uri?.let { listOf(if (engine == ContainerEngine.DOCKER) "--host" else "--url", it) } ?: emptyList()) +
            arguments
}

public data class EndpointConfig(
    val engine: ContainerEngine? = null,
    val uri: String? = null,
) {
    public companion object {
        public fun fromEnvironment(env: Map<String, String> = System.getenv()): EndpointConfig {
            val engine = when (env["TIER_E_CONTAINER_ENGINE"]?.lowercase()) {
                "docker" -> ContainerEngine.DOCKER
                "podman" -> ContainerEngine.PODMAN
                else -> null
            }
            return EndpointConfig(
                engine,
                env["MAZEWALL_DOCKER_HOST"]?.takeIf { it.isNotBlank() }
                    ?: env["DOCKER_HOST"]?.takeIf { it.isNotBlank() }
                    ?: env["CONTAINER_HOST"]?.takeIf { it.isNotBlank() },
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
    private val probe: EndpointProbe = CliEndpointProbe(),
) {
    public fun discover(config: EndpointConfig = EndpointConfig.fromEnvironment()): EndpointSelection {
        val candidates = if (config.engine != null || config.uri != null) {
            listOf(ContainerEndpoint(config.engine ?: ContainerEngine.DOCKER, config.uri))
        } else {
            listOf(ContainerEndpoint(ContainerEngine.DOCKER, null), ContainerEndpoint(ContainerEngine.PODMAN, null))
        }
        val reasons = mutableListOf<String>()
        for (candidate in candidates) {
            when (val result = probe.probe(candidate)) {
                EndpointProbeResult.Rootful -> return EndpointSelection.Ready(candidate)
                EndpointProbeResult.Rootless -> reasons += "${candidate.engine} is rootless"
                is EndpointProbeResult.Unavailable -> reasons += "${candidate.engine}: ${result.reason}"
            }
        }
        return EndpointSelection.Skipped("No usable rootful container endpoint: ${reasons.joinToString("; ")}")
    }
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
): ContainerCommandResult {
    val output = Files.createTempFile("mazewall-container-probe-", ".log")
    try {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        } catch (error: java.io.IOException) {
            return ContainerCommandResult(-1, error.message.orEmpty())
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            return ContainerCommandResult(-1, "container endpoint probe timed out")
        }
        return ContainerCommandResult(process.exitValue(), Files.readString(output).take(4096))
    } finally {
        Files.deleteIfExists(output)
    }
}
