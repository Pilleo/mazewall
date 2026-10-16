package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Pins a matrix to one local image, native agent and tooling distribution. */
public object TierEMatrixProvenance {
    public data class Identity(
        val imageId: String,
        val nativeSha256: String,
        val jarsSha256: String,
    ) {
        public fun json(): JsonObject =
            buildJsonObject {
            put("image_id", imageId)
            put("native_so_sha256", nativeSha256)
            put("daemon_jars_sha256", jarsSha256)
            put("policy_eligibility", false)
        }

        public fun unchanged(
            root: Path,
            endpoint: ContainerEndpoint,
            image: String,
            native: Path,
            execute: (List<String>) -> ContainerCommandResult = { runContainerCommand(it) },
        ): Boolean =
            try {
            capture(root, endpoint, image, native, execute) == this
        } catch (_: Exception) {
            false
        }
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
        .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    public fun capture(
        root: Path,
        endpoint: ContainerEndpoint,
        image: String,
        native: Path,
        execute: (List<String>) -> ContainerCommandResult = { runContainerCommand(it) },
    ): Identity {
        val inspected = execute(endpoint.command("image", "inspect", image, "--format", "{{.Id}}"))
        require(inspected.exitCode == 0 && inspected.output.trim().startsWith("sha256:")) { "Tier E image identity unavailable" }
        val jars = root.resolve("profiler/build/install/tier-e-daemon/lib")
        val jarLines = Files.list(jars).use { entries ->
            entries
                .filter { it.fileName.toString().endsWith(".jar") }
                .map { "${sha(Files.readAllBytes(it))}  ${it.toAbsolutePath()}" }
                .sorted()
                .toList()
        }
        require(jarLines.isNotEmpty()) { "Tier E daemon tooling distribution missing" }
        return Identity(inspected.output.trim(), sha(Files.readAllBytes(native)), sha((jarLines.joinToString("\n") + "\n").toByteArray()))
    }
}
