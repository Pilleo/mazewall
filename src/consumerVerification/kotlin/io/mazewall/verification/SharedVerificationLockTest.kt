package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SharedVerificationLockTest {
    @TempDir
    private lateinit var directory: Path

    @Test
    fun `fresh normal and linked repositories share a portable flock path`() {
        git(directory, "init", "--quiet")
        val linked = directory.resolve("linked")
        git(directory, "worktree", "add", "--orphan", "-b", "lock-fixture", linked.toString())
        val first = SharedVerificationLock.resolve(directory)
        assertEquals(first, SharedVerificationLock.resolve(linked))
        assertFalse(first.toString().contains(".superpowers"))
        SharedVerificationLock.acquire(first).use { }
    }

    @Test
    fun `lease serializes competing processes and releases after failure`() {
        val path = directory.resolve("verification.lock")
        SharedVerificationLock.acquire(path).use {
            val contender = ProcessBuilder("flock", "-n", path.toString(), "true").start()
            assertEquals(1, contender.waitFor())
        }
        assertThrows(IllegalStateException::class.java) {
            SharedVerificationLock.acquire(path).use { error("fixture failure") }
        }
        val contender = ProcessBuilder("flock", "-n", path.toString(), "true").start()
        assertEquals(0, contender.waitFor())
    }

    private fun git(
        root: Path,
        vararg arguments: String,
    ) {
        val child = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, child.waitFor(), output)
    }
}
