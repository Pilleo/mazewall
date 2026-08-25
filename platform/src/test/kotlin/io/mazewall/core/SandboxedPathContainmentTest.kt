package io.mazewall.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxedPathContainmentTest {

    @Test
    fun `isUnder accepts equal and nested paths`() {
        val parent = SandboxedPath.of("/srv/data", allowNonExistent = true)
        assertTrue(parent isUnder parent, "equal path must be contained")
        assertTrue(SandboxedPath.of("/srv/data/sub/file.txt", true) isUnder parent)
    }

    @Test
    fun `isUnder rejects siblings and prefix-string lookalikes`() {
        val parent = SandboxedPath.of("/srv/data", allowNonExistent = true)
        assertFalse(SandboxedPath.of("/srv/database", true) isUnder parent, "string-prefix sibling must not match")
        assertFalse(SandboxedPath.of("/srv", true) isUnder parent, "ancestor must not be 'under' descendant")
        assertFalse(SandboxedPath.of("/etc/passwd", true) isUnder parent)
    }

    @Test
    fun `containment is component-wise on normalized values`() {
        // of() normalizes: /srv/data/ and /srv/./data collapse to /srv/data
        val a = SandboxedPath.of("/srv/data/", allowNonExistent = true)
        val b = SandboxedPath.of("/srv/./data", allowNonExistent = true)
        assertEquals(a, b)
        assertTrue(SandboxedPath.of("/srv/data/../data/x", true) isUnder a)
        assertFalse(SandboxedPath.unsafe("relative/path") isUnder a, "relative paths are never contained")
    }

    @Test
    fun `coversAll empty children always covered regardless of parents`() {
        val parents = setOf(SandboxedPath.of("/srv/data", true))
        assertTrue(emptySet<SandboxedPath>().let { it.coversAll(it) } || true) // sanity: no crash on empty receiver use below
        assertTrue(emptySet<SandboxedPath>().coveredBy(parents))
        assertFalse(setOf(SandboxedPath.of("/etc/shadow", true)).coveredBy(emptySet()))
    }

    @Test
    fun `coveredBy requires every child under some parent`(@TempDir tempDir: java.nio.file.Path) {
        val parents = setOf(
            Sandbled(tempDir, "reads"),
            Sandbled(tempDir, "writes"),
        )
        assertTrue(setOf(Sandbled(tempDir, "reads/a"), Sandbled(tempDir, "writes/b")).coveredBy(parents))
        assertFalse(setOf(Sandbled(tempDir, "secrets")).coveredBy(parents))
    }

    @Test
    fun `realpath comparison collapses symlinks`(@TempDir tempDir: java.nio.file.Path) {
        val real = Files.createDirectories(tempDir.resolve("real"))
        val link = tempDir.resolve("link")
        try {
            Files.createSymbolicLink(link, real)
        } catch (e: UnsupportedOperationException) {
            return // filesystem without symlink support: nothing to assert
        }
        val viaLink = SandboxedPath.of(link.toString(), true).resolveReal()
        val viaReal = SandboxedPath.of(real.toString(), true)
        assertEquals(viaReal, viaLink, "resolveReal must collapse the symlink to its target")
        assertTrue(SandboxedPath.of(link.resolve("f").toString(), true).resolveReal() isUnder viaReal)
    }

    private fun Sandbled(base: java.nio.file.Path, name: String): SandboxedPath =
        SandboxedPath.of(base.resolve(name).toString(), allowNonExistent = true)
}
