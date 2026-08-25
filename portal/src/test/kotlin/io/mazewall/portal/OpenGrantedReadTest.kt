package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpenGrantedReadTest {
    @Test
    fun `absolute relative path is rejected without syscall`() {
        assertThrows(IllegalArgumentException::class.java) {
            openGrantedRead(Path.of("/tmp"), "/etc/passwd")
        }
    }

    @Test
    fun `symlink escaping the root does not yield a granted FD`(@TempDir root: Path) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val outside = Files.createTempFile("mazewall-outside-", ".txt")
        Files.writeString(outside, "secret")
        val link = root.resolve("escape")
        Files.createSymbolicLink(link, outside)
        assertThrows(IllegalStateException::class.java) {
            openGrantedRead(root, "escape")
        }
        Files.deleteIfExists(outside)
    }

    @Test
    fun `dot-dot escape does not yield a granted FD`(@TempDir root: Path) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        assertThrows(IllegalStateException::class.java) {
            openGrantedRead(root, "../passwd")
        }
    }
}
