package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConsumerFixtureTest {
    @TempDir
    private lateinit var root: Path

    @Test
    fun `disposable checkout includes actual edits additions deletions and modes but no build outputs`() {
        val repository = Files.createDirectory(root.resolve("original"))
        val evidence = Files.createDirectory(root.resolve("evidence"))

        fun git(vararg args: String) {
            assertEquals(0, ConsumerFixture.command(listOf("git") + args, repository, evidence, "git-${Files.list(evidence).use { it.count() }}").exitCode)
        }
        git("init")
        Files.writeString(repository.resolve(".gitignore"), "/build/\n")
        Files.writeString(repository.resolve("changed"), "original")
        Files.writeString(repository.resolve("deleted"), "old")
        git("add", ".")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture")
        Files.writeString(repository.resolve("changed"), "modified")
        Files.delete(repository.resolve("deleted"))
        Files.writeString(repository.resolve("new"), "new actual source")
        repository.resolve("new").toFile().setExecutable(true)
        Files.createSymbolicLink(repository.resolve("alias"), Path.of("new"))
        Files.createDirectory(repository.resolve("build"))
        Files.writeString(repository.resolve("build/unidentified-output"), "must not copy")
        val before = ConsumerFixture.snapshot(repository)
        val clone = ConsumerFixture.checkout(repository)
        assertEquals("modified", Files.readString(clone.resolve("changed")))
        assertEquals("new actual source", Files.readString(clone.resolve("new")))
        assertTrue(Files.isExecutable(clone.resolve("new")))
        assertTrue(Files.isSymbolicLink(clone.resolve("alias")))
        assertFalse(Files.exists(clone.resolve("deleted")))
        assertFalse(Files.exists(clone.resolve("build")))
        Files.writeString(clone.resolve("changed"), "failed producer mutation")
        assertEquals(before, ConsumerFixture.snapshot(repository))
    }
}
