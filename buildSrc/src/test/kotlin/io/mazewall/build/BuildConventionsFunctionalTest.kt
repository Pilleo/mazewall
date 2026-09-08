package io.mazewall.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BuildConventionsFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `conventions expose the shared JVM quality and publishing contract`() {
        Files.writeString(
            projectDir.resolve("settings.gradle.kts"),
            """
            dependencyResolutionManagement {
                versionCatalogs {
                    create("libs") {
                        version("jacoco", "0.8.14")
                        version("ktlintEngine", "1.7.0")
                    }
                }
            }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        Files.writeString(
            projectDir.resolve("build.gradle.kts"),
            """
            plugins {
                id("mazewall.base-conventions")
                id("mazewall.jvm-library-conventions")
                id("mazewall.test-conventions")
                id("mazewall.quality-conventions")
                id("mazewall.publishing-conventions")
            }

            tasks.register("assertConventions") {
                doLast {
                    check(project.group == "io.mazewall") { "unexpected group: ${'$'}{project.group}" }
                    check(project.version == "0.0.1-prealpha-SNAPSHOT") { "unexpected version: ${'$'}{project.version}" }
                    check(tasks.findByName("test") != null) { "test task missing" }
                    check(tasks.findByName("ktlintCheck") != null) { "ktlintCheck task missing" }
                    check(tasks.findByName("spotbugsMain") != null) { "spotbugsMain task missing" }
                    check(pluginManager.hasPlugin("maven-publish")) { "maven-publish plugin missing" }
                    check(project.extensions.findByName("publishing") != null) { "publishing extension missing despite applied plugin" }
                }
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("assertConventions", "--stacktrace")
                .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":assertConventions")?.outcome)
    }
}
