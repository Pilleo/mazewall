package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@Tag("lockdown-consumer")
class LockdownPackagedConsumerTest {
    @TempDir
    private lateinit var tempRoot: Path

    @Test
    fun `staged lockdown publication satisfies JDK 22 and 25 classpath, JPMS, and Kotlin consumers`() {
        val root = Path.of(System.getProperty("consumerWorkspace")?.takeIf(String::isNotBlank) ?: error("Missing consumerWorkspace"))
        val repository = Files.createDirectories(tempRoot.resolve("repo"))
        val evidence = Files.createDirectories(tempRoot.resolve("evidence"))
        val version = "0.1.0-consumer.${System.nanoTime()}"
        val compiler = System.getProperty("consumerKotlinVersion", "2.4.21")
        val jdks =
            System
                .getProperty("consumerJdks", "22,25")
                .split(",")
                .map { it.trim().toInt() }
        val gradlew = root.resolve("gradlew").toString()

        val stageArgs =
            listOf(
                gradlew,
                "--console=plain",
                "--no-build-cache",
                "--no-configuration-cache",
                "--init-script",
                root.resolve("tests/consumers/stage.init.gradle").toString(),
                "-PconsumerVersion=$version",
                "-PconsumerRepository=$repository",
                ":lockdown:verifyLockdownPublication",
                ":lockdown:publishMavenJavaPublicationToConsumerBaselineRepository",
            )
        val stageResult = ConsumerFixture.command(stageArgs, root, evidence, "stage-lockdown", Duration.ofMinutes(10))
        assertEquals(0, stageResult.exitCode) { "Lockdown staging failed:\n${stageResult.stdout}\n${stageResult.stderr}" }

        val lockdownDir = repository.resolve("io/mazewall/mazewall-lockdown/$version")
        listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module").forEach { suffix ->
            val artifact = lockdownDir.resolve("mazewall-lockdown-$version$suffix")
            assertTrue(Files.isRegularFile(artifact) && Files.size(artifact) > 0L) { "Missing staged file: $artifact" }
        }
        assertFalse(Files.exists(repository.resolve("io/mazewall/platform"))) { "Unrelated :platform must not be staged" }
        assertFalse(Files.exists(repository.resolve("io/mazewall/enforcer"))) { "Unrelated :enforcer must not be staged" }

        val consumerDir = root.resolve("tests/consumers/lockdown")
        for (jdk in jdks) {
            val consumerArgs =
                listOf(
                    gradlew,
                    "-p",
                    consumerDir.toString(),
                    "--console=plain",
                    "--no-build-cache",
                    "--no-configuration-cache",
                    "-PconsumerRepository=$repository",
                    "-PconsumerVersion=$version",
                    "-PconsumerKotlinVersion=$compiler",
                    "-PconsumerJdk=$jdk",
                    "clean",
                    "verifyAll",
                )
            val run = ConsumerFixture.command(consumerArgs, root, evidence, "lockdown-jdk$jdk", Duration.ofMinutes(10))
            val combined = run.stdout + "\n" + run.stderr
            assertEquals(0, run.exitCode) { "JDK $jdk lockdown consumer failed:\n$combined" }
            listOf(
                "JAVA_CLASSPATH_LOCKDOWN_OK",
                "JAVA_ENCAPSULATION_OK",
                "JAVA_JPMS_LOCKDOWN_OK",
                "JPMS_ENCAPSULATION_OK",
                "KOTLIN_LOCKDOWN_OK",
                "JAVA_EXPECTED_FAILURE_OK:",
            ).forEach { marker ->
                assertTrue(marker in combined) { "JDK $jdk output missing marker '$marker':\n$combined" }
            }
        }
    }
}
