package demo.vulnapp.config

import io.mazewall.profiler.BillOfBehavior
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

internal object ProfileArtifactPublisher {
    fun integrityPath(target: Path): Path = target.resolveSibling("${target.fileName}.integrity.json")

    fun publish(target: Path, evidence: BillOfBehavior, drain: ExecutorDrain) {
        target.parent?.let(Files::createDirectories)
        val content = evidence.toJson()
        publishText(target, content)
        val complete = drain is ExecutorDrain.Complete
        val reason = if (drain is ExecutorDrain.Incomplete) drain.reason else "complete"
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        publishText(
            integrityPath(target),
            """{"complete":$complete,"reason":"${escape(reason)}","sha256":"$digest"}""",
        )
    }

    fun publishText(target: Path, content: String) {
        target.parent?.let(Files::createDirectories)
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
