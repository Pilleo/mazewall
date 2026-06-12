package io.mazewall

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Paths

/**
 * Orchestrates the execution of core library tests inside a Linux container
 * with the required capabilities and seccomp profile.
 *
 * This replaces 'scripts/run_tests.sh'.
 */
@Testcontainers
class ContainerizedTestRunner {
    companion object {
        private val projectRoot = Paths.get(".").toAbsolutePath().normalize()
        private val seccompProfile = projectRoot.resolve("infra/dev/podman-seccomp.json")

        @Container
        @JvmStatic
        private val mazewall: GenericContainer<*> = GenericContainer(
            ImageFromDockerfile("mazewall-test-runner", false)
                .withDockerfile(projectRoot.resolve("infra/dev/Containerfile")),
                // No need to send the whole root as we bind mount it
        ).withFileSystemBind(projectRoot.toString(), "/workspace")
            .withFileSystemBind(System.getProperty("user.home") + "/.gradle", "/root/.gradle")
            .withEnv("GRADLE_USER_HOME", "/root/.gradle")
            .withWorkingDirectory("/workspace")
            .withCreateContainerCmdModifier { cmd ->
                cmd.withHostConfig(
                    cmd.hostConfig
                        ?.withNetworkMode("host")
                        ?.withUsernsMode("host")
                        ?.withSecurityOpts(listOf("seccomp=$seccompProfile")),
                )
                cmd.withCapAdd(
                    com.github.dockerjava.api.model.Capability.AUDIT_READ,
                    com.github.dockerjava.api.model.Capability.AUDIT_CONTROL,
                )
            }.withCommand("tail", "-f", "/dev/null")
    }

    @Test
    fun `run core library tests in container`() {
        val tasksProp = System.getProperty(
            "mazewall.container.tasks",
            ":enforcer:test :profiler:test :enforcer:integrationTest :profiler:integrationTest",
        )
        val tasks = tasksProp.split(" ").filter { it.isNotBlank() }.toTypedArray()
        runGradleTasks(*tasks)
    }

    private fun runGradleTasks(vararg tasks: String) {
        if (tasks.isEmpty()) return

        val command = mutableListOf("./gradlew")
        command.addAll(tasks)
        command.addAll(listOf("--info", "--no-daemon"))

        println("Executing tasks: ${tasks.joinToString(", ")}")
        val result = mazewall.execInContainer(*command.toTypedArray())

        if (result.exitCode != 0) {
            println("STDOUT:\n${result.stdout}")
            println("STDERR:\n${result.stderr}")
        }

        assertEquals(0, result.exitCode, "Gradle tasks failed in container with exit code ${result.exitCode}: ${tasks.joinToString(", ")}")
    }
}
