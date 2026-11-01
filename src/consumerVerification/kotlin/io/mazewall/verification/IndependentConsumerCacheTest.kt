package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration

@Tag("build-logic")
class IndependentConsumerCacheTest {
    @TempDir private lateinit var root: Path

    @Test fun `final root acceptance retains staged version and artifact namespace`() {
        val project = Files.createDirectory(root.resolve("producer"))
        for (name in listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties", "tests/consumers/stage.init.gradle")) {
            val target = project.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(Path.of(name), target, COPY_ATTRIBUTES)
        }
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='staged-consumer-fixture'\n")
        Files.writeString(
            project.resolve("build.gradle"),
            """
            plugins { id 'java'; id 'maven-publish' }
            version = '0.1.0-SNAPSHOT'
            tasks.register('consumerNativeCheck') {
                dependsOn tasks.jar
                outputs.upToDateWhen { false }
                outputs.cacheIf { false }
                doLast {
                    if (project.version.toString() != providers.gradleProperty('consumerVersion').get()) {
                        throw new GradleException('Staging version context lost: ' + project.version)
                    }
                    file('build/native-entry.txt').append('executed\n')
                }
            }
        """.trimIndent(),
        )
        val source = project.resolve("src/main/java/Entry.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "public class Entry {}\n")
        val evidence = Files.createDirectory(root.resolve("version-evidence"))
        val properties = listOf("-PconsumerVersion=0.1.0-integration.fixture", "-PconsumerRepository=${root.resolve("repository")}")
        val staged = ConsumerFixture.command(
            listOf(
                project.resolve("gradlew").toString(),
                "-I",
                project.resolve("tests/consumers/stage.init.gradle").toString(),
                "--no-configuration-cache",
                "--console=plain",
                "--max-workers=1",
                "jar",
            ) +
                properties,
            project,
            evidence,
            "stage",
            Duration.ofMinutes(2),
        )
        assertEquals(0, staged.exitCode, staged.stdout + staged.stderr)
        val jar = project.resolve("build/libs/staged-consumer-fixture-0.1.0-integration.fixture.jar")
        val hash = BootFixture.hash(jar)
        val accepted = ConsumerFixture.command(ConsumerFixture.stagedGradle(project) + "consumerNativeCheck" + properties, project, evidence, "root-acceptance", Duration.ofMinutes(2))
        assertEquals(0, accepted.exitCode, accepted.stdout + accepted.stderr)
        assertTrue(":compileJava UP-TO-DATE" in accepted.stdout && ":jar UP-TO-DATE" in accepted.stdout, accepted.stdout)
        assertEquals(hash, BootFixture.hash(jar))
        assertEquals("executed\n", Files.readString(project.resolve("build/native-entry.txt")))
    }

    @Test fun `fresh consumer copies reuse compilation and host tests but execute native tasks`() {
        val effects = Files.createDirectory(root.resolve("effects"))
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
        val dependencies = junit.joinToString(",") { "uri('$it')" }
        val application = Files.readString(Path.of("demos/spring-report-api/build.gradle"))

        fun policy(name: String): String =
            application
                .substringAfter("tasks.register('$name', Test) {")
            .substringBefore("\n}")
                .lineSequence()
                .filter { it.trimStart().startsWith("outputs.") }
                .joinToString("\n")

        fun fixture(
            copy: String,
            cacheHome: Path,
        ): Path {
            val project = Files.createDirectories(root.resolve("$copy/spring-report-api"))
            Files.writeString(
                project.resolve("settings.gradle"),
                "rootProject.name='consumer-cache-fixture'\nbuildCache { local { directory = file('${cacheHome.resolve("caches/build-cache-1")}') } }\n",
            )
            Files.writeString(project.resolve("gradle.properties"), "org.gradle.caching=false\norg.gradle.workers.max=1\n")
            Files.createDirectories(project.resolve("gradle"))
            Files.writeString(
                project.resolve("gradle/verification-metadata.xml"),
                """
                <verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
                  <configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>
                  <components/>
                </verification-metadata>
            """.trimIndent(),
            )
            Files.writeString(
                project.resolve("build.gradle"),
                """
                plugins { id 'java' }
                dependencies { testImplementation files($dependencies) }
                tasks.withType(Test).configureEach {
                    useJUnitPlatform()
                    systemProperty 'fixture.effects', '$effects'
                    systemProperty 'fixture.task', name
                }
                tasks.register('nativeTest', Test) {
                    testClassesDirs = sourceSets.test.output.classesDirs
                    classpath = sourceSets.test.runtimeClasspath
                    ${policy("nativeTest")}
                }
                tasks.register('presentationTest', Test) {
                    testClassesDirs = sourceSets.test.output.classesDirs
                    classpath = sourceSets.test.runtimeClasspath
                    ${policy("presentationTest")}
                }
            """.trimIndent(),
            )
            val source = project.resolve("src/main/java/Business.java")
            Files.createDirectories(source.parent)
            Files.writeString(source, "public class Business { public static int value() { return 1; } }\n")
            val test = project.resolve("src/test/java/BusinessTest.java")
            Files.createDirectories(test.parent)
            Files.writeString(
                test,
                """
                import java.nio.file.*;
                public class BusinessTest {
                    @org.junit.jupiter.api.Test void recordsActualWork() throws Exception {
                        Path target = Path.of(System.getProperty("fixture.effects"), System.getProperty("fixture.task") + ".txt");
                        Files.writeString(target, Business.value() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                }
            """.trimIndent(),
            )
            return project
        }
        val firstCacheHome = root.resolve("first-container-home")
        val secondCacheHome = root.resolve("second-container-home")
        val first = fixture("first", firstCacheHome)
        val firstEvidence = Files.createDirectory(root.resolve("first-evidence"))
        PitchExamplesAcceptance.buildConsumer(
            listOf(Path.of("gradlew").toAbsolutePath().toString(), "-p", first.toString(), "--console=plain", "--max-workers=1"),
            first,
            firstEvidence,
            listOf("test", "nativeTest", "presentationTest"),
        )
        val firstCache = firstCacheHome.resolve("caches/build-cache-1")
        val secondCache = secondCacheHome.resolve("caches/build-cache-1")
        assertTrue(Files.isDirectory(firstCache), "First build must create the Gradle build-cache subtree")
        Files.walk(firstCache).use { paths ->
            paths.forEach { source ->
                val target = secondCache.resolve(firstCache.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target, REPLACE_EXISTING, COPY_ATTRIBUTES)
                }
            }
        }
        val project = fixture("second", secondCacheHome)
        val evidence = Files.createDirectory(root.resolve("second-evidence"))
        val arguments = Json
            .parseToJsonElement(Files.readString(firstEvidence.resolve("spring-report-api-strict.command.json")))
            .jsonObject
            .getValue("argv")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .map { if (it == first.toString()) project.toString() else it }
        val second = ConsumerFixture.command(arguments, project, evidence, "copied-strict", Duration.ofMinutes(2))
        assertEquals(0, second.exitCode, second.stdout + second.stderr)
        assertTrue(":compileJava FROM-CACHE" in second.stdout, second.stdout)
        assertTrue(":test FROM-CACHE" in second.stdout, second.stdout)
        assertEquals(listOf("1"), Files.readAllLines(effects.resolve("test.txt")))
        for (name in listOf("nativeTest", "presentationTest")) assertEquals(listOf("1", "1"), Files.readAllLines(effects.resolve("$name.txt")))
        Files.writeString(project.resolve("src/main/java/Business.java"), "public class Business { public static int value() { return 2; } }\n")
        val changed = ConsumerFixture.command(arguments, project, evidence, "changed-input", Duration.ofMinutes(2))
        assertEquals(0, changed.exitCode, changed.stdout + changed.stderr)
        assertFalse(":compileJava FROM-CACHE" in changed.stdout, changed.stdout)
        assertFalse(":test FROM-CACHE" in changed.stdout, changed.stdout)
        assertEquals(listOf("1", "2"), Files.readAllLines(effects.resolve("test.txt")))
        for (name in listOf("nativeTest", "presentationTest")) assertEquals(listOf("1", "1", "2"), Files.readAllLines(effects.resolve("$name.txt")))
    }
}
