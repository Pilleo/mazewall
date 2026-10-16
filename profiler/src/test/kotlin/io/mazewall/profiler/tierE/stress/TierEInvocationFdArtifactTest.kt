package io.mazewall.profiler.tierE.stress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class TierEInvocationFdArtifactTest {
    @TempDir lateinit var directory: Path

    private fun artifact(
        firstId: Long = 0x4d5a0000002a,
        secondId: Long = 0x4d5a0000002b,
        drops: Long = 0,
    ): Path {
        val data = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        data.put(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte()))
        data
            .putShort(1)
            .putShort(16)
            .putInt(0x4d5a)
            .putInt(0)
        data.putLong(2).putLong(drops)
        data.putLong(firstId).putInt(13).putInt(0)
        data.putLong(secondId).putInt(14).putInt(0)
        return directory.resolve("invocation-fd.mzif").also { Files.write(it, data.array()) }
    }

    @Test
    fun `joins target-authored declared FDs to actual published marker IDs`() {
        assertEquals(
            mapOf(13 to 0x4d5a0000002a, 14 to 0x4d5a0000002b),
            TierEInvocationFdArtifact.read(artifact(), setOf(13, 14), 0x4d5a),
        )
    }

    @Test
    fun `rejects dropped duplicate and absent declared operation tokens`() {
        assertThrows(IllegalStateException::class.java) { TierEInvocationFdArtifact.read(artifact(drops = 1), setOf(13, 14), 0x4d5a) }
        assertThrows(IllegalStateException::class.java) { TierEInvocationFdArtifact.read(artifact(secondId = 0x4d5a0000002a), setOf(13, 14), 0x4d5a) }
        assertThrows(IllegalStateException::class.java) { TierEInvocationFdArtifact.read(artifact(), setOf(13, 14, 15), 0x4d5a) }
        assertThrows(IllegalStateException::class.java) { TierEInvocationFdArtifact.read(artifact(), setOf(13, 14), 0x4d5b) }
    }
}
