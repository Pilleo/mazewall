package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.time.Duration

class CiEfficiencyTest {
    @TempDir private lateinit var root: Path

    @Test fun `runner image export refuses missing failed and empty archives`() {
        val core = steps("core-quality")
        val script = script(core.getValue("Save All Podman Images to Cache Folder"))
        val bin = Files.createDirectory(root.resolve("bin"))
        val podman = Files.writeString(
            bin.resolve("podman"),
            """
            #!/bin/sh
            set -eu
            case "${'$'}1 ${'$'}2" in
                'image exists')
                    case "${'$'}3" in mazewall-test-runner|localhost/mazewall-test-runner) test "${'$'}MODE" != missing ;; *) exit 1 ;; esac ;;
                'save -o')
                    test "${'$'}MODE" != failed || exit 23
                    if [ "${'$'}MODE" = empty ]; then : > "${'$'}3"; else printf 'owned runner archive' > "${'$'}3"; fi ;;
                *) exit 64 ;;
            esac
            """.trimIndent() + "\n",
        )
        assertTrue(podman.toFile().setExecutable(true))
        val failures = mutableListOf<String>()
        for (mode in listOf("missing", "failed", "empty", "valid")) {
            val workspace = Files.createDirectory(root.resolve(mode))
            val exports = "export PATH=\"\$1:\$PATH\" GITHUB_WORKSPACE=\"\$2\" MODE=\"\$3\"\n"
            val result = command(
                listOf("bash", "-euo", "pipefail", "-c", exports + script.replace("\${{ github.workspace }}", workspace.toString()), "image-fixture", bin.toString(), workspace.toString(), mode),
                workspace,
            )
            if ((result.exitCode == 0) != (mode == "valid")) failures += "$mode export returned ${result.exitCode}"
            if (mode == "valid") assertTrue(Files.size(workspace.resolve(".cache/podman-image/mazewall-test-runner.tar")) > 0)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        val condition = core
            .getValue("Upload Podman Image Cache")
            .lineSequence()
            .map(String::trim)
            .first { it.startsWith("if: ") }
            .removePrefix("if: ")
            .replace("always()", "true")
        for (hit in listOf("true", "false", "")) {
            for (outcome in listOf("success", "failure", "cancelled", "skipped", "")) {
            val expression = condition
                .replace("steps.cache-podman.outputs.cache-hit", "'$hit'")
                .replace("steps.runner_image_export.outcome", "'$outcome'")
            assertFalse("steps." in expression)
            val saved = command(listOf("bash", "-euc", "[[ $expression ]]"), root).exitCode == 0
            assertEquals(hit != "true" && outcome == "success", saved, "Cache hit='$hit', export='$outcome'")
        }
        }
    }

    @Test fun `optional adapter cache proof restores its graph without reexecuting build tasks`() {
        val project = Files.createDirectory(root.resolve("adapters"))
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.workers.max=1\n")
        val modules = listOf("integrations:koog", "integrations:spring:mazewall-spring-boot", "integrations:spring:mazewall-spring-boot-starter")
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='adapter-cache-fixture'\ninclude " + modules.joinToString(",") { "':$it'" } + "\n")
        modules.forEach { Files.createDirectories(project.resolve(it.replace(':', '/'))) }
        Files.writeString(
            project.resolve("build.gradle"),
            """
            def observed = providers.fileContents(layout.projectDirectory.file('generated.flag')).asText.orElse('absent').get()
            abstract class CountRuns extends DefaultTask {
                @OutputFile abstract RegularFileProperty getCounter()
                @OutputFile abstract RegularFileProperty getGenerated()
                @TaskAction void count() {
                    counter.get().asFile.append('executed\n')
                    generated.get().asFile.text = 'created'
                }
            }
            allprojects {
                tasks.register('build', CountRuns) {
                    counter.set(rootProject.layout.projectDirectory.file(project.path.replace(':', '-') + '.count'))
                    generated.set(rootProject.layout.projectDirectory.file('generated.flag'))
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                }
            }
            """.trimIndent(),
        )
        copyWrapper(project)
        var proof = ""
        val selected = setOf(
            "Build Optional Adapters With Strict Dependency and Configuration Cache Checks",
            "Prime Optional Adapter Post-Build Configuration Cache",
            "Prove Optional Adapter Configuration Cache Reuse",
        )
        for ((name, step) in steps("optional-integrations")) {
            if (name !in selected) continue
            val result = command(listOf("bash", "-euo", "pipefail", "-c", script(step)), project)
            assertEquals(0, result.exitCode, result.stdout + result.stderr)
            if (name == "Prove Optional Adapter Configuration Cache Reuse") proof = result.stdout
        }
        assertTrue("Configuration cache entry reused" in proof, proof)
        for (module in modules) assertEquals("executed\n", Files.readString(project.resolve("-${module.replace(':', '-')}.count")))
        assertFalse(Files.exists(project.resolve("-.count")), "Unselected root work must not execute")
    }

    @Test fun `owned child coverage agent cache identity follows bytes rather than Gradle home`() {
        val project = Files.createDirectory(root.resolve("coverage"))
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='coverage-cache-fixture'\nbuildCache { local { directory = file('owned-cache') } }\n")
        val input = Files
            .readString(Path.of("build.gradle.kts"))
            .substringAfter("val agent = files(configurations.named(\"jacocoAgent\"))")
            .substringAfter("unitTest.configure {")
            .substringBefore("outputs.dir(childExecutionDirectory)")
            .trimIndent()
        Files.writeString(
            project.resolve("build.gradle"),
            """
            @CacheableTask abstract class Capture extends DefaultTask {
                @OutputFile abstract RegularFileProperty getReceipt()
                @TaskAction void capture() { receipt.get().asFile.text = 'qualified' }
            }
            def agent = files(providers.gradleProperty('agentJar').get())
            def unitTest = tasks.register('capture', Capture) { receipt.set(layout.buildDirectory.file('receipt.txt')) }
            unitTest.configure {
                $input
            }
            """.trimIndent(),
        )
        copyWrapper(project)
        val hostAgent = Files.createDirectories(root.resolve("host-gradle-home")).resolve("agent.jar")
        val containerAgent = Files.createDirectories(root.resolve("container-gradle-home")).resolve("agent.jar")
        Files.writeString(hostAgent, "same coverage agent bytes")
        Files.copy(hostAgent, containerAgent)
        val receipt = project.resolve("build/receipt.txt")

        fun capture(agent: Path): ConsumerCommandResult {
            val result =
                command(
                    listOf(
                        project.resolve("gradlew").toString(),
                        "-p",
                        project.toString(),
                        "capture",
                        "-PagentJar=$agent",
                        "--build-cache",
                        "--no-configuration-cache",
                        "--no-daemon",
                        "--max-workers=1",
                        "--console=plain",
                    ),
                    project,
                )
            assertEquals(0, result.exitCode, result.stdout + result.stderr)
            assertEquals("qualified", Files.readString(receipt))
            return result
        }
        capture(hostAgent)
        Files.delete(receipt)
        val moved = capture(containerAgent)
        assertTrue(":capture FROM-CACHE" in moved.stdout, moved.stdout)
        Files.writeString(containerAgent, "different coverage agent bytes")
        Files.delete(receipt)
        assertFalse(":capture FROM-CACHE" in capture(containerAgent).stdout)
    }

    private fun copyWrapper(project: Path) {
        for (name in listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            val target = project.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(Path.of(name), target, COPY_ATTRIBUTES)
        }
    }

    private fun command(
        arguments: List<String>,
        project: Path,
    ): ConsumerCommandResult = ConsumerFixture.command(arguments, project, Files.createTempDirectory(root, "evidence-"), "command", Duration.ofMinutes(2))

    private fun steps(job: String): Map<String, String> {
        val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))
        val block = Regex("(?ms)^    $job:\\n.*?(?=^    [a-zA-Z0-9_-]+:|\\z)").find(workflow)?.value ?: error("Missing job $job")
        return block.split("\n            - name: ").drop(1).associateBy { it.substringBefore('\n') }
    }

    private fun script(step: String): String {
        val lines = step
            .substringAfter("run: ", "")
            .lineSequence()
            .drop(1)
            .takeWhile { it.isBlank() || it.startsWith("                  ") }
        return if ("run: >-" in step) lines.joinToString(" ") { it.trim() } else lines.joinToString("\n") { it.removePrefix("                  ") }
    }
}
