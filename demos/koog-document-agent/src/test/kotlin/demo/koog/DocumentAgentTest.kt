package demo.koog

import demo.koog.DocumentReader.readBoundedDocument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentAgentTest {
    @TempDir lateinit var root: Path

    @Test fun `reader returns actual strict bounded document content`() {
        val note = Files.writeString(root.resolve("note.txt"), "Project owner: Ada é🙂")
        assertEquals("Project owner: Ada é🙂", readBoundedDocument(note))
        Files.writeString(note, "changed")
        assertEquals("changed", readBoundedDocument(note))
    }

    @Test fun `reader accepts exact byte limit and rejects oversized or malformed UTF8`() {
        val note = Files.writeString(root.resolve("note.txt"), "é".repeat(32_768))
        assertEquals("é".repeat(32_768), readBoundedDocument(note))
        Files.writeString(note, "x".repeat(65_537))
        assertFailsWith<IllegalArgumentException> { readBoundedDocument(note) }
        Files.write(note, byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<CharacterCodingException> { readBoundedDocument(note) }
    }

    @Test fun `path validation rejects blank NUL and excessive UTF8 bytes`() {
        for (bad in listOf("", " ", "bad\u0000path", "\uD800", "x".repeat(4097), "é".repeat(2048) + "x")) {
            assertFailsWith<IllegalArgumentException> { validateReadArguments(ReadDocumentArguments(bad)) }
        }
        validateReadArguments(ReadDocumentArguments("é".repeat(2048)))
    }
}
