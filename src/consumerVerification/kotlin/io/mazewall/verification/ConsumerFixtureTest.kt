package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConsumerFixtureTest {
    @TempDir
    private lateinit var root: Path

    @Test fun `optional integration evidence stays in unique checkout owned report directories`() {
        val first = ConsumerFixture.optionalIntegrationEvidence(root)
        val second = ConsumerFixture.optionalIntegrationEvidence(root)
        assertTrue(first.startsWith(root.resolve("build/reports/optional-integrations")))
        assertTrue(first != second && Files.isDirectory(first) && Files.isDirectory(second))
    }

    @Test fun `diagnostic root refuses aliases before writing outside the checkout`() {
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(workspace.resolve("build"), outside)
        assertThrows(IllegalArgumentException::class.java) { ConsumerFixture.optionalIntegrationEvidence(workspace) }
        Files.list(outside).use { assertFalse(it.findAny().isPresent) }
    }

    @Test fun `report aliases fail retention without exposing contents or replacing the original failure`() {
        val project = Files.createDirectory(root.resolve("consumer"))
        val reports = Files.createDirectories(project.resolve("build/test-results/nativeTest"))
        val outside = Files.writeString(root.resolve("private-fixture"), "not a report")
        Files.createSymbolicLink(reports.resolve("alias.xml"), outside)
        val evidence = root.resolve("retained")
        val original = IllegalStateException("original command failure")
        val caught = assertThrows(IllegalStateException::class.java) {
            AutoCloseable { ConsumerFixture.retainTestReports(project, evidence) }.use { throw original }
        }
        assertSame(original, caught)
        assertTrue(caught.suppressed.single() is IllegalArgumentException)
        assertFalse(Files.exists(evidence.resolve("test-results/nativeTest/alias.xml")))
    }

    @Test fun `failure before test execution needs no invented report or replacement failure`() {
        val project = Files.createDirectory(root.resolve("consumer"))
        val evidence = root.resolve("retained")
        val original = IllegalStateException("compile failed before tests")
        val caught = assertThrows(IllegalStateException::class.java) {
            AutoCloseable { ConsumerFixture.retainTestReports(project, evidence) }.use { throw original }
        }
        assertSame(original, caught)
        assertEquals(0, caught.suppressed.size)
        assertFalse(Files.exists(evidence))
    }

    @Test fun `failed consumer retains full JUnit and HTML reports without copying caches or masking failure`() {
        val project = Files.createDirectory(root.resolve("consumer"))
        val xml = project.resolve("build/test-results/nativeTest/TEST-failed.xml")
        Files.createDirectories(xml.parent)
        Files.writeString(xml, "<testsuite><failure>actual nested failure</failure></testsuite>")
        val html = project.resolve("build/reports/tests/nativeTest/index.html")
        Files.createDirectories(html.parent)
        Files.writeString(html, "actual full report")
        Files.writeString(project.resolve("build/private-cache.bin"), "must not export")
        val evidence = root.resolve("retained")
        val original = IllegalStateException("original failed command")
        val caught = assertThrows(IllegalStateException::class.java) {
            AutoCloseable { ConsumerFixture.retainTestReports(project, evidence) }.use { throw original }
        }
        assertSame(original, caught)
        assertEquals(Files.readString(xml), Files.readString(evidence.resolve("test-results/nativeTest/TEST-failed.xml")))
        assertEquals(Files.readString(html), Files.readString(evidence.resolve("reports/tests/nativeTest/index.html")))
        assertFalse(Files.exists(evidence.resolve("private-cache.bin")))
    }

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
