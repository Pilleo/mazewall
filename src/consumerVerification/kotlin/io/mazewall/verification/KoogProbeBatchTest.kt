package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.time.Duration

@Tag("build-logic")
class KoogProbeBatchTest {
    @TempDir private lateinit var root: Path

    @Test fun `one Gradle command runs fresh probes in distinct child JVMs and stops on the original child failure`() {
        val project = Files.createDirectory(root.resolve("fixture"))
        copyWrapper(project)
        val effects = root.resolve("effects.log")
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='koog-probe-batch'\n")
        Files.writeString(project.resolve("build.gradle"), fixtureBuild(effects))
        val source = project.resolve("src/main/java/fixture/Probe.java")
        Files.createDirectories(source.parent)
        Files.writeString(
            source,
            """
            package fixture;
            import java.nio.file.*;
            public final class Probe {
              public static void main(String[] args) throws Exception {
                Files.writeString(Path.of(args[1]), args[0] + "|" + ProcessHandle.current().pid() + "|" + System.getProperty("user.dir") + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (args[0].equals(args[2])) System.exit(23);
              }
            }
            """.trimIndent(),
        )
        val evidence = Files.createDirectory(root.resolve("evidence"))

        fun command(
            label: String,
            fail: String = "",
        ): ConsumerCommandResult {
            val arguments = listOf(
                project.resolve("gradlew").toString(),
                "--console=plain",
                "--max-workers=1",
                "--no-configuration-cache",
                "--build-cache",
                "-Peffects=$effects",
                "-Pfail=$fail",
            )
            return ConsumerFixture.command(
                arguments + KOOG_FRESH_PROBE_TASKS,
                project,
                evidence,
                label,
                Duration.ofMinutes(2),
            )
        }

        val success = command("fresh-batch")
        assertEquals(0, success.exitCode, success.stdout + success.stderr)
        requireKoogFreshTaskExecutions(success.stdout + success.stderr)
        val records = Files.readAllLines(effects)
        assertEquals("closure", records.first())
        assertEquals(KOOG_FRESH_PROBE_TASKS, records.drop(1).map { it.substringBefore('|') })
        val pids = records.drop(1).map { it.split('|')[1] }.toSet()
        val directories = records.drop(1).map { it.split('|')[2] }.toSet()
        assertEquals(4, pids.size)
        assertEquals(4, directories.size)

        Files.delete(effects)
        val failed = command("fresh-batch-failure", "verifyFreshThreadNative")
        assertFalse(failed.exitCode == 0, failed.stdout + failed.stderr)
        assertTrue("23" in failed.stdout + failed.stderr, failed.stdout + failed.stderr)
        assertEquals(
            listOf("closure", "verifyFreshNative", "verifyFreshThreadNative"),
            Files.readAllLines(effects).map { it.substringBefore('|') },
        )
        val receipt = Json
            .parseToJsonElement(Files.readString(evidence.resolve("fresh-batch-failure.command.json")))
            .jsonObject
        assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
    }

    private fun fixtureBuild(effects: Path): String =
        """
        import java.nio.file.Files
        plugins { id 'java' }
        def selected = ${KOOG_FRESH_PROBE_TASKS.joinToString(
            prefix = "[",
            postfix = "]",
        ) { "'$it'" }}
        tasks.register('verifyPublishedClosure') { doLast { file(findProperty('effects')).append('closure' + System.lineSeparator()) } }
        selected.each { selectedName ->
          tasks.register(selectedName, JavaExec) {
            dependsOn 'verifyPublishedClosure', 'classes'
            classpath = sourceSets.main.runtimeClasspath
            mainClass = 'fixture.Probe'
            args selectedName, findProperty('effects'), findProperty('fail') ?: ''
            outputs.upToDateWhen { false }; outputs.cacheIf { false }
            doFirst { workingDir = Files.createTempDirectory('koog-batch-').toFile() }
          }
        }
        selected.drop(1).eachWithIndex { name, index -> tasks.named(name) { mustRunAfter selected[index] } }
        """.trimIndent()

    private fun copyWrapper(project: Path) {
        for (name in listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            val target = project.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(Path.of(name), target, COPY_ATTRIBUTES)
        }
    }
}
