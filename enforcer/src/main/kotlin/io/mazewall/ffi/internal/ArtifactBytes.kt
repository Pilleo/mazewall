package io.mazewall.ffi.internal

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Shared bounded byte operations; limits are input rejection, never a partial digest. */
internal object ArtifactBytes {
    const val MAX_ARTIFACT_BYTES = 256L * 1024 * 1024
    const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 4096

    fun digest(
        path: Path,
        maximum: Long = MAX_ARTIFACT_BYTES,
    ): String = Files.newInputStream(path).use { digest(it, maximum) }

    fun digest(
        input: InputStream,
        maximum: Long = MAX_ARTIFACT_BYTES,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        var count = input.read(buffer)
        while (count >= 0) {
            total += count
            require(total <= maximum) { "Implementation exceeds bounded identity budget" }
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun read(
        input: InputStream,
        maximum: Int = MAX_CLASS_BYTES,
    ): ByteArray {
        val bytes = input.readNBytes(maximum + 1)
        require(bytes.size <= maximum) { "Selected entry exceeds bounded identity budget" }
        return bytes
    }

    fun requireEntryName(name: String) {
        require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains('\u0000')) { "Unsupported archive entry name" }
        require(name.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Archive entry traverses its origin" }
    }
}
