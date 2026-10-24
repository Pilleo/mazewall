package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KoogSpringPublicationTest {
    @TempDir
    lateinit var repository: Path

    private val version = "0.1.0-integration.123456789abc"
    private val original = """
        <verification-metadata><configuration><verify-metadata>true</verify-metadata></configuration><components>
        <component group="external" name="pinned" version="1"><artifact name="pinned.jar"><sha256 value="unchanged"/></artifact></component>
        <component group="io.mazewall" name="enforcer" version="0.1.0-old"><artifact name="old.jar"><sha256 value="historical"/></artifact></component>
        </components></verification-metadata>
    """.trimIndent()

    @Test
    fun `pins only the six exact publication coordinates and preserves existing approvals`() {
        publications()
        val updated = withKoogSpringPublicationChecksums(original, repository, version)
        val additions = updated.substringAfter("version=\"0.1.0-old\"").substringAfter("</component>").substringBefore("</components>")
        assertEquals(original, updated.replace(additions, "\n"))
        assertEquals(6, Regex("<component ").findAll(additions).count())
        assertEquals(18, Regex("<artifact ").findAll(additions).count())
        assertEquals(18, Regex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad").findAll(additions).count())
        assertTrue(additions.contains("name=\"koog\" version=\"$version\""))
        assertThrows<IllegalStateException> { withKoogSpringPublicationChecksums(updated, repository, version) }
    }

    @Test
    fun `missing staged bytes and unsafe namespaces cannot create approvals`() {
        assertThrows<IllegalArgumentException> { withKoogSpringPublicationChecksums(original, repository, "../escape") }
        assertThrows<IllegalArgumentException> { withKoogSpringPublicationChecksums(original, repository, version) }
    }

    private fun publications() {
        for (module in listOf("platform", "enforcer", "runtime", "koog", "mazewall-spring-boot", "mazewall-spring-boot-starter")) {
            val directory = Files.createDirectories(repository.resolve("io/mazewall/$module/$version"))
            for (extension in listOf("jar", "pom", "module")) Files.writeString(directory.resolve("$module-$version.$extension"), "abc")
        }
    }
}
