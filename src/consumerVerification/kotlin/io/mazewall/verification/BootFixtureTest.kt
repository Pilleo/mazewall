package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BootFixtureTest {
    @TempDir private lateinit var root: Path

    @Test
    fun `finished package must contain all four actual publication bytes and advertised launcher`() {
        val repository = Files.createDirectory(root.resolve("repository"))
        val modules = listOf("platform", "enforcer", "mazewall-spring-boot", "mazewall-spring-boot-starter")
        for ((index, module) in modules.withIndex()) {
            val path = repository.resolve("io/mazewall/$module/0.1-fixture/$module-0.1-fixture.jar")
            Files.createDirectories(path.parent)
            Files.write(path, byteArrayOf(index.toByte()))
        }

        fun archive(
            name: String,
            changed: String? = null,
            security: Boolean = false,
            launcher: String = "org.springframework.boot.loader.launch.JarLauncher",
        ): Path {
            val target = root.resolve("$name.jar")
            ZipOutputStream(Files.newOutputStream(target)).use { zip ->
                fun entry(
                    path: String,
                    bytes: ByteArray,
                ) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: $launcher\nSpring-Boot-Version: 4.1.1\n\n".toByteArray())
                entry("BOOT-INF/classes/demo/boot/ReportService.class", byteArrayOf(1))
                for ((index, module) in modules.withIndex()) entry("BOOT-INF/lib/$module-0.1-fixture.jar", byteArrayOf(if (module == changed) 99 else index.toByte()))
                if (security) entry("BOOT-INF/lib/spring-security-core.jar", byteArrayOf(1))
            }
            return target
        }
        assertEquals(modules.toSet(), BootFixture.inspectJar(archive("valid"), repository, "0.1-fixture").keys)
        for (module in modules) assertThrows<IllegalStateException> { BootFixture.inspectJar(archive("changed-$module", changed = module), repository, "0.1-fixture") }
        assertThrows<IllegalStateException> { BootFixture.inspectJar(archive("security", security = true), repository, "0.1-fixture") }
        assertThrows<IllegalStateException> { BootFixture.inspectJar(archive("launcher", launcher = "unreviewed.Main"), repository, "0.1-fixture") }
        Files.delete(repository.resolve("io/mazewall/platform/0.1-fixture/platform-0.1-fixture.jar"))
        assertThrows<IllegalStateException> { BootFixture.inspectJar(root.resolve("valid.jar"), repository, "0.1-fixture") }
    }

    @Test
    fun `source custody rejects changed or unidentified producer inputs`() {
        val repository = Files.createDirectory(root.resolve("source"))
        val evidence = Files.createDirectory(root.resolve("evidence"))
        var sequence = 0

        fun git(vararg args: String): String {
            val result = ConsumerFixture.command(listOf("git") + args, repository, evidence, "git-${++sequence}")
            assertEquals(0, result.exitCode, result.stderr)
            return result.stdout.trim()
        }
        git("init")
        for (name in listOf(
                "build.gradle.kts",
                "demos/spring-boot-sandbox/build.gradle",
                "demos/spring-boot-sandbox/settings.gradle",
                "demos/spring-boot-sandbox/src/main/java/App.java",
                "src/consumerVerification/kotlin/Review.kt",
                "tests/consumers/spring-boot/optional-modules.init.gradle",
            )) {
            val path = repository.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, "known source")
        }
        git("add", ".")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture")
        val revision = git("rev-parse", "HEAD")
        assertTrue(BootFixture.sourceIdentity(repository, revision, evidence).containsKey("producerInputs"))
        Files.writeString(repository.resolve("build.gradle.kts"), "changed producer")
        assertThrows<IllegalStateException> { BootFixture.sourceIdentity(repository, revision, evidence) }
        Files.writeString(repository.resolve("build.gradle.kts"), "known source")
        Files.createDirectory(repository.resolve("platform"))
        Files.writeString(repository.resolve("platform/unknown.kt"), "unidentified input")
        assertThrows<IllegalStateException> { BootFixture.sourceIdentity(repository, revision, evidence) }
    }
}
