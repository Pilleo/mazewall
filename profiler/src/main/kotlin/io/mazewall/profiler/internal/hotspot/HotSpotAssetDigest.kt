package io.mazewall.profiler.internal.hotspot

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val SHA256_BYTES = 32
private const val MAX_HASH_BYTES = 536_870_912L

internal fun interface HotSpotAssetDigest {
    fun digest(
        path: Path,
        maximum: Long,
    ): HotSpotFileDigest
}

internal class HotSpotFileDigest(
    val bytesHashed: Long,
    digest: ByteArray,
) {
    private val owned = digest.copyOf()

    init {
        require(bytesHashed >= 0 && owned.size == SHA256_BYTES)
    }

    fun bytes(): ByteArray = owned.copyOf()
}

internal object JavaHotSpotAssetDigest : HotSpotAssetDigest {
    override fun digest(
        path: Path,
        maximum: Long,
    ): HotSpotFileDigest {
        require(maximum in 0..MAX_HASH_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(bytes)
                if (count == -1) break
                require(count.toLong() <= maximum - total) { "HotSpot asset hash byte budget exceeded" }
                total += count
                digest.update(bytes, 0, count)
            }
        }
        return HotSpotFileDigest(total, digest.digest())
    }
}
