package io.mazewall.sbob

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathNormalizerSymlinkTest {
    @Test
    fun `test pruning does not occur if parent is a symlink`(@TempDir tempDir: Path) {
        val realParent = tempDir.resolve("real_parent")
        Files.createDirectories(realParent)
        val fileInRealParent = realParent.resolve("file.txt")
        Files.writeString(fileInRealParent, "hello")

        val symlinkParent = tempDir.resolve("symlink_parent")
        Files.createSymbolicLink(symlinkParent, realParent)

        val paths = setOf(
            symlinkParent.toString(),
            symlinkParent.resolve("file.txt").toString()
        )

        // Pruning should NOT occur because symlinkParent is a symlink.
        // Both paths should remain in the set.
        val result = PathNormalizer.normalizeAndPrune(paths, null)

        assertEquals(
            setOf(symlinkParent.toString(), symlinkParent.resolve("file.txt").toString()),
            result
        )
    }

    @Test
    fun `test pruning works for real directories`(@TempDir tempDir: Path) {
        val realParent = tempDir.resolve("real_parent")
        Files.createDirectories(realParent)
        val fileInRealParent = realParent.resolve("file.txt")
        Files.writeString(fileInRealParent, "hello")

        val paths = setOf(
            realParent.toString(),
            fileInRealParent.toString()
        )

        // Pruning SHOULD occur because realParent is a directory.
        val result = PathNormalizer.normalizeAndPrune(paths, null)

        assertEquals(setOf(realParent.toString()), result)
    }
}
