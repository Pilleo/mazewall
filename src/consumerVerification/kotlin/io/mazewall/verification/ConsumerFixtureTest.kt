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
import java.time.Duration

class ConsumerFixtureTest {
    @TempDir
    private lateinit var root: Path

    @Test fun `failed JUnit module in a disposable producer clone retains its real failure reports`() {
        val repository = Files.createDirectory(root.resolve("original"))
        val evidence = Files.createDirectory(root.resolve("evidence"))
        Files.writeString(repository.resolve(".gitignore"), "/build/\n**/build/\n.gradle/\n")
        Files.writeString(repository.resolve("settings.gradle"), "rootProject.name='failed-producer-fixture'\ninclude ':demos:cli-demo'\n")
        val junit = listOf(
            Test::class.java,
            Class.forName("org.junit.jupiter.engine.JupiterTestEngine"),
            Class.forName("org.junit.platform.engine.TestEngine"),
            Class.forName("org.junit.platform.commons.JUnitException"),
            Class.forName("org.junit.platform.launcher.Launcher"),
            Class.forName("org.opentest4j.AssertionFailedError"),
        ).map {
            it.protectionDomain.codeSource.location
            .toURI()
            .toASCIIString()
        }.distinct()
        val files = junit.joinToString(",") { "uri('${it.replace("'", "\\'")}')" }
        val module = Files.createDirectories(repository.resolve("demos/cli-demo"))
        Files.writeString(module.resolve("build.gradle"), "plugins { id 'java' }\ndependencies { testImplementation files($files) }\ntest { useJUnitPlatform() }\n")
        val source = module.resolve("src/test/java/LegacyFailureTest.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "public class LegacyFailureTest { @org.junit.jupiter.api.Test void fails() { org.junit.jupiter.api.Assertions.fail(\"DELIBERATE_MODULE_FAILURE\"); } }\n")
        var sequence = 0

        fun git(vararg args: String) {
            assertEquals(0, ConsumerFixture.command(listOf("git") + args, repository, evidence, "git-${sequence++}").exitCode)
        }
        git("init", "--quiet")
        git("add", ".")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture")
        val checkout = ConsumerFixture.checkout(repository)
        val original = IllegalStateException("actual cloned module failed")
        val caught = assertThrows(IllegalStateException::class.java) {
            ConsumerFixture.producerReports(checkout, evidence).use {
                val result = ConsumerFixture.command(
                    listOf(Path.of("gradlew").toAbsolutePath().toString(), "-p", checkout.toString(), ":demos:cli-demo:test", "--no-configuration-cache", "--console=plain", "--max-workers=1"),
                    checkout,
                    evidence,
                    "failed-module",
                    Duration.ofMinutes(2),
                )
                assertTrue(result.exitCode != 0, result.stdout + result.stderr)
                val failedReport = checkout.resolve("demos/cli-demo/build/test-results/test/TEST-LegacyFailureTest.xml")
                assertTrue(Files.isRegularFile(failedReport), result.stdout + result.stderr)
                assertTrue(Files.readString(failedReport).contains("DELIBERATE_MODULE_FAILURE"))
                throw original
            }
        }
        assertSame(original, caught)
        val retained = evidence.resolve("demos-cli-demo-reports/test-results/test/TEST-LegacyFailureTest.xml")
        assertTrue(Files.readString(retained).contains("DELIBERATE_MODULE_FAILURE"))
        assertEquals(Files.readString(checkout.resolve("demos/cli-demo/build/test-results/test/TEST-LegacyFailureTest.xml")), Files.readString(retained))
        assertTrue(Files.isRegularFile(evidence.resolve("demos-cli-demo-reports/reports/tests/test/index.html")))
    }

    @Test fun `producer retention collects other modules while preserving all retention failures`() {
        val checkout = Files.createDirectory(root.resolve("checkout"))
        val outside = Files.createDirectory(root.resolve("outside"))
        for (name in listOf("integrations/koog", "demos/koog-contained-tool")) {
            val alias = checkout.resolve("$name/build/test-results")
            Files.createDirectories(alias.parent)
            Files.createSymbolicLink(alias, outside)
        }
        val report = checkout.resolve("demos/cli-demo/build/test-results/test/TEST-good.xml")
        Files.createDirectories(report.parent)
        Files.writeString(report, "actual safe report")
        val evidence = root.resolve("retained")
        val original = IllegalStateException("original failed command")
        val caught = assertThrows(IllegalStateException::class.java) {
            ConsumerFixture.producerReports(checkout, evidence).use { throw original }
        }
        assertSame(original, caught)
        assertEquals("actual safe report", Files.readString(evidence.resolve("demos-cli-demo-reports/test-results/test/TEST-good.xml")))
        val retention = caught.suppressed.single()
        assertTrue(retention is IllegalArgumentException && retention.suppressed.single() is IllegalArgumentException)
        Files.list(outside).use { assertFalse(it.findAny().isPresent) }
        assertThrows(IllegalArgumentException::class.java) {
            ConsumerFixture.producerReports(checkout, root.resolve("otherwise-successful")).use { }
        }
    }

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

    @Test fun `intermediate source aliases cannot export outside files and preserve the primary failure`() {
        for (name in listOf("build", "build/reports")) {
            val case = Files.createDirectory(root.resolve(name.replace('/', '-')))
            val project = Files.createDirectory(case.resolve("consumer"))
            val outside = Files.createDirectory(case.resolve("outside"))
            val sentinel = outside.resolve(if (name == "build") "test-results/nativeTest/private.txt" else "tests/nativeTest/private.txt")
            Files.createDirectories(sentinel.parent)
            Files.writeString(sentinel, "outside sentinel, not a report")
            val alias = project.resolve(name)
            Files.createDirectories(alias.parent)
            Files.createSymbolicLink(alias, outside)
            val evidence = case.resolve("retained")
            val original = IllegalStateException("original command failure")
            val caught = assertThrows(IllegalStateException::class.java) {
                AutoCloseable { ConsumerFixture.retainTestReports(project, evidence) }.use { throw original }
            }
            assertFalse(Files.exists(evidence), "Intermediate source alias exported outside files")
            assertSame(original, caught)
            assertEquals(1, caught.suppressed.size)
            assertTrue(caught.suppressed.single() is IllegalArgumentException)
            assertEquals("outside sentinel, not a report", Files.readString(sentinel))
        }
    }

    @Test fun `intermediate destination aliases cannot write outside the evidence directory`() {
        for (name in listOf("root", "reports")) {
            val case = Files.createDirectory(root.resolve(name))
            val project = Files.createDirectory(case.resolve("consumer"))
            val report = project.resolve("build/reports/tests/nativeTest/index.html")
            Files.createDirectories(report.parent)
            Files.writeString(report, "actual report")
            val outside = Files.createDirectory(case.resolve("outside"))
            val sentinel = Files.writeString(outside.resolve("sentinel"), "unchanged")
            val evidence = case.resolve("retained")
            val alias = if (name == "root") evidence else Files.createDirectory(evidence).resolve("reports")
            Files.createSymbolicLink(alias, outside)
            val original = IllegalStateException("original command failure")
            val caught = assertThrows(IllegalStateException::class.java) {
                AutoCloseable { ConsumerFixture.retainTestReports(project, evidence) }.use { throw original }
            }
            Files.list(outside).use { assertEquals(listOf(sentinel), it.toList(), "Report retention wrote through an intermediate alias") }
            assertSame(original, caught)
            assertEquals(1, caught.suppressed.size)
            assertTrue(caught.suppressed.single() is IllegalArgumentException)
        }
    }

    @Test fun `unreadable report parents are retained as errors rather than mistaken for missing reports`() {
        val project = Files.createDirectory(root.resolve("consumer"))
        val report = project.resolve("build/reports/tests/nativeTest/index.html")
        Files.createDirectories(report.parent)
        Files.writeString(report, "actual report")
        val protectedParent = project.resolve("build/reports")
        val permissions = Files.getPosixFilePermissions(protectedParent)
        try {
            Files.setPosixFilePermissions(protectedParent, emptySet())
            assertFalse(Files.isReadable(protectedParent), "Permission fixture requires an unprivileged UID")
            val original = IllegalStateException("original command failure")
            val caught = assertThrows(IllegalStateException::class.java) {
                AutoCloseable { ConsumerFixture.retainTestReports(project, root.resolve("retained")) }.use { throw original }
            }
            assertSame(original, caught)
            assertEquals(1, caught.suppressed.size)
            assertTrue(caught.suppressed.single() is java.nio.file.AccessDeniedException)
        } finally {
            Files.setPosixFilePermissions(protectedParent, permissions)
        }
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
