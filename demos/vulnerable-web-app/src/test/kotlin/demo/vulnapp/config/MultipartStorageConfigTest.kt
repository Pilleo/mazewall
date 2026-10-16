package demo.vulnapp.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties
import org.springframework.util.unit.DataSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultipartStorageConfigTest {
    @TempDir lateinit var root: Path

    @Test
    fun `staging is a real owned directory while configured upload limits remain intact`() {
        val uploads = Files.createDirectory(root.resolve("uploads"))
        val properties = MultipartProperties().apply {
            maxFileSize = DataSize.ofBytes(2048)
            maxRequestSize = DataSize.ofBytes(4096)
            fileSizeThreshold = DataSize.ofBytes(128)
        }
        val config = MultipartStorageConfig(ServiceResourceBindings(root, uploads)).multipartConfigElement(properties)
        val staging = Path.of(config.location)
        assertEquals(uploads.toRealPath(), staging.parent)
        assertTrue(Files.isDirectory(staging))
        assertFalse(Files.isSymbolicLink(staging))
        assertEquals(2048L, config.maxFileSize)
        assertEquals(4096L, config.maxRequestSize)
        assertEquals(128, config.fileSizeThreshold)
    }

    @Test
    fun `an outside configured location or a staging symlink is refused without creating outside data`() {
        val uploads = Files.createDirectory(root.resolve("uploads"))
        val outside = Files.createDirectory(root.resolve("outside"))
        val config = MultipartStorageConfig(ServiceResourceBindings(root, uploads))
        val properties = MultipartProperties().apply { location = outside.resolve("not-created").toString() }
        assertFailsWith<IllegalArgumentException> { config.multipartConfigElement(properties) }
        assertFalse(Files.exists(outside.resolve("not-created")))
        Files.createSymbolicLink(uploads.resolve(".multipart"), outside)
        assertFailsWith<IllegalArgumentException> { config.multipartConfigElement(MultipartProperties()) }
        assertTrue(Files.isSymbolicLink(uploads.resolve(".multipart")), "Failed validation must not rewrite an existing entry")
    }
}
