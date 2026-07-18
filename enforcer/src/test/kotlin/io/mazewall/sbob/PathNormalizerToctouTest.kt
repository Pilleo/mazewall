package io.mazewall.sbob

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathNormalizerToctouTest {
    @Test
    fun `test normalization with symlinks and dotdot`(@TempDir tempDir: Path) {
        val dir1 = tempDir.resolve("dir1")
        val dir2 = tempDir.resolve("dir2")
        Files.createDirectories(dir1)
        Files.createDirectories(dir2)

        val linkToDir2 = dir1.resolve("link_to_dir2")
        Files.createSymbolicLink(linkToDir2, dir2)

        // Path: tempDir/dir1/link_to_dir2/../target.txt
        // Syntactically, it is tempDir/dir1/target.txt
        // Physically, it is tempDir/dir2/../target.txt which is tempDir/target.txt

        val targetInDir1 = dir1.resolve("target.txt")
        Files.writeString(targetInDir1, "wrong")

        val targetInTemp = tempDir.resolve("target.txt")
        Files.writeString(targetInTemp, "right")

        val pathWithDotDot = linkToDir2.resolve("..").resolve("target.txt").toString()

        val result = PathNormalizer.normalizeAndPrune(setOf(pathWithDotDot), null)

        // Now we expect physical resolution
        val expectedPhysical = tempDir.resolve("target.txt").toRealPath().toString()

        println("Result: $result")
        println("Expected Physical: $expectedPhysical")

        assertEquals(setOf(expectedPhysical), result)
    }
}
