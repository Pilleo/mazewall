package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OpenGrantedReadTest {
    @Test
    fun `absolute relative path is rejected without syscall`() {
        assertThrows(IllegalArgumentException::class.java) {
            openGrantedRead(Path.of("/tmp"), "/etc/passwd")
        }
    }

    @Test
    fun `symlink escaping the root does not yield a granted FD`(
        @TempDir root: Path,
    ) {
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
    fun `dot-dot escape does not yield a granted FD`(
        @TempDir root: Path,
    ) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        assertThrows(IllegalStateException::class.java) {
            openGrantedRead(root, "../passwd")
        }
    }

    @Test
    fun `writerless FIFO is rejected without blocking while regular file clears O_NONBLOCK`(
        @TempDir root: Path,
    ) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val fifo = root.resolve("input.fifo")
        val mkfifo = ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start()
        assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, mkfifo.exitValue())

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                openGrantedRead(root, "input.fifo")
            }
        assertEquals("granted path is not a regular file: input.fifo", error.message)

        val regular = root.resolve("input.txt")
        Files.writeString(regular, "ok")
        openGrantedRead(root, "input.txt").use { capability ->
            capability.borrow { fd ->
                val flags = LinuxNative.raw.fcntl(fd, io.mazewall.ffi.NativeConstants.F_GETFL, 0L).getOrThrow("getfl")
                assertEquals(0L, flags and io.mazewall.ffi.NativeConstants.O_NONBLOCK)
            }
        }
    }
}
