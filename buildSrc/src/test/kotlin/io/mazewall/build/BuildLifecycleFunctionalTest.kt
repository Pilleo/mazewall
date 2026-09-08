package io.mazewall.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BuildLifecycleFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `unit and kernel lifecycles are disjoint and compilation is read only`() {
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
            include(":library")
            """.trimIndent(),
        )
        Files.createDirectories(projectDir.resolve("library/src/main/kotlin/example"))
        Files.writeString(projectDir.resolve("library/src/main/kotlin/example/Example.kt"), "package example\nclass Example\n")
        Files.writeString(
            projectDir.resolve("library/build.gradle.kts"),
            """
            plugins { id("mazewall.quality-conventions") }
            tasks.register("integrationTest")
            tasks.register("integrationTestFreshJvm")
            tasks.register("jacocoKernelTestReport")
            tasks.register("jacocoKernelCoverageVerification")
            """.trimIndent(),
        )

        val unit = run(":library:unitCheck", "--dry-run")
        assertFalse(unit.output.lineSequence().any { it.startsWith(":library:integrationTest ") })

        val kernel = run(":library:kernelCheck", "--dry-run")
        assertTrue(kernel.output.lineSequence().any { it.startsWith(":library:integrationTest ") })
        assertTrue(kernel.output.lineSequence().any { it.startsWith(":library:integrationTestFreshJvm ") })

        val compile = run(":library:compileKotlin", "--dry-run")
        assertFalse(compile.output.contains("ktlintFormat"))

        val check = run(":library:check", "--dry-run")
        assertFalse(check.output.contains("ktlintFormat"))
        assertFalse(check.output.contains("integrationTest"))
    }

    @Test
    fun `settings reject project-owned repositories`() {
        Files.writeString(
            projectDir.resolve("settings.gradle.kts"),
            """
            import org.gradle.api.initialization.resolve.RepositoriesMode
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories { mavenCentral() }
            }
            """.trimIndent(),
        )
        Files.writeString(projectDir.resolve("build.gradle.kts"), "repositories { mavenCentral() }")

        val failure =
            org.junit.jupiter.api.assertThrows<UnexpectedBuildFailure> {
                run("help")
            }
        assertTrue(failure.buildResult.output.contains("repository 'MavenRepo' was added by build file"))
    }

    private fun run(vararg arguments: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")
            .build()
}
