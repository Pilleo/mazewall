package io.mazewall.demo.koog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThreadReadToolTest {
    @TempDir
    private lateinit var root: Path

    @Test
    fun `reader counts UTF8 bytes and rejects the first byte beyond 64 KiB`() {
        val maximum = Files.writeString(root.resolve("maximum.txt"), "é".repeat(32768))
        val oversized = Files.writeString(root.resolve("oversized.txt"), "é".repeat(32768) + "x")
        assertEquals("é".repeat(32768), readBoundedUtf8(maximum))
        assertFailsWith<IllegalArgumentException> { readBoundedUtf8(oversized) }
    }

    @Test
    fun `reader rejects malformed UTF8 without replacing it`() {
        val malformed = Files.write(root.resolve("malformed.txt"), byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<CharacterCodingException> { readBoundedUtf8(malformed) }
    }

    @Test
    fun `missing file remains its actual IO failure`() {
        assertFailsWith<NoSuchFileException> { readBoundedUtf8(root.resolve("missing.txt")) }
    }
}
