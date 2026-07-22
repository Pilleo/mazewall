package io.mazewall.sbob

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathNormalizerSymlinkTest {
    @Test
    fun `test pruning DOES occur if parent is a symlink because it is resolved`(@TempDir tempDir: Path) {
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

        // Pruning SHOULD occur because symlinkParent is resolved to its real path.
        val result = PathNormalizer.normalizeAndPrune(paths, null)

        val expectedPath = realParent.toRealPath().toString()
        assertEquals(setOf(expectedPath), result)
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

        assertEquals(setOf(realParent.toRealPath().toString()), result)
    }

    @Test
    fun `test symlink with dot dot non existent`(@TempDir tempDir: Path) {
        val subDir = tempDir.resolve("sub")
        val dir = subDir.resolve("dir")
        Files.createDirectories(dir)

        val symlink = tempDir.resolve("sym_link")
        Files.createSymbolicLink(symlink, dir)

        // Path: tempDir/sym_link/../other_file
        // Physically: tempDir/sub/dir/../other_file -> tempDir/sub/other_file
        // Syntactically: tempDir/other_file
        val pathStr = symlink.resolve("../other_file").toString()

        val result = PathNormalizer.normalizeAndPrune(setOf(pathStr), null)

        val expectedPhysical = subDir.resolve("other_file").toAbsolutePath().normalize().toString()
        assertEquals(setOf(expectedPhysical), result)
    }
}
