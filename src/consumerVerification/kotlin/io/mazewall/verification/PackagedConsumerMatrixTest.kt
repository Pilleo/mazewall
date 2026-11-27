package io.mazewall.verification

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

@Tag("packaged-consumer")
class PackagedConsumerMatrixTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
    fun `real pinned publications satisfy Java and stable Kotlin consumers on JDK 25`() {
        val root = Path.of(required("consumerWorkspace"))
        val repository = Path.of(required("consumerRepository"))
        val version = required("consumerVersion")
        val compiler = System.getProperty("consumerKotlinVersion", "2.4.20")
        require(repository.isAbsolute && Files.isDirectory(repository)) { "An existing absolute publication repository is required" }
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+[-.a-zA-Z0-9]*").matches(version)) { "Invalid pinned candidate version" }
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(compiler)) { "Consumer compiler must be a stable pinned release" }
        val evidence = Path.of(System.getProperty("consumerEvidence", root.resolve("build/consumer-evidence-${System.nanoTime()}").toString()))
        val lock = SharedVerificationLock.resolve(root, System.getProperty("verificationLock"))
        SharedVerificationLock.acquire(lock, waitSeconds = 60).use {
            Files.createDirectory(evidence.also { Files.createDirectories(it.parent) })
            val hashes = buildList {
                for (module in listOf("platform", "enforcer")) {
                    for (extension in listOf("jar", "pom", "module")) {
                        val artifact = repository.resolve("io/mazewall/$module/$version/$module-$version.$extension")
                        require(Files.isRegularFile(artifact)) { "Missing real Maven publication: $artifact" }
                        val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)).joinToString("") { "%02x".format(it) }
                        add("$sha  $artifact")
                    }
                }
            }
            Files.write(evidence.resolve("artifact-sha256.txt"), hashes, CREATE_NEW)
            val gitTimeout = Duration.ofSeconds(15)
            val revision = checkedCommand(listOf("git", "rev-parse", "HEAD"), root, evidence, "source-revision", gitTimeout)
            Files.writeString(evidence.resolve("source-revision.txt"), revision.stdout, CREATE_NEW)
            val status = checkedCommand(listOf("git", "status", "--short"), root, evidence, "worktree-status", gitTimeout)
            Files.writeString(evidence.resolve("worktree-status.txt"), status.stdout, CREATE_NEW)
            checkedCommand(listOf(root.resolve("gradlew").toString(), "--version"), root, evidence, "gradle-version", Duration.ofSeconds(30))

            val arguments = packagedConsumerArguments(root, repository, version, compiler, System.getProperty("consumerWriteLocks") == "true")
            runPackagedConsumer(root, evidence, arguments, Duration.ofMinutes(6))
        }
    }

    private fun required(name: String): String = System.getProperty(name)?.takeIf(String::isNotBlank) ?: error("Missing required -P$name")
}

internal fun packagedConsumerArguments(
    root: Path,
    repository: Path,
    version: String,
    compiler: String,
    writeLocks: Boolean,
): List<String> =
    buildList {
        add(root.resolve("gradlew").toString())
        add("-p")
        add(root.resolve("tests/consumers").toString())
        add("--console=plain")
        add("--no-daemon")
        add("--no-build-cache")
        add("--no-configuration-cache")
        add("-PconsumerRepository=$repository")
        add("-PconsumerVersion=$version")
        add("-PconsumerKotlinVersion=$compiler")
        add("-PconsumerJdk=25")
        if (writeLocks) add("--write-locks")
        addAll(
            listOf(
                ":java-api:clean",
                ":kotlin-api:clean",
                ":java-api:consumerEnvironment",
                ":java-api:run",
                ":java-api:verifyCallableEntry",
                ":java-api:verifyScopeBoundaries",
                ":java-api:verifyRejectedApi",
                ":kotlin-api:consumerEnvironment",
                ":kotlin-api:run",
                ":kotlin-api:verifyRejectedApi",
                ":kotlin-api:verifyScopeBoundaries",
                "resolveIntegrationBaselines",
            ),
        )
    }

internal fun runPackagedConsumer(
    root: Path,
    evidence: Path,
    arguments: List<String>,
    timeout: Duration,
) {
    Files.write(evidence.resolve("jdk25.argv"), arguments, CREATE_NEW)
    checkedCommand(arguments, root, evidence, "jdk25", timeout)
}

private fun checkedCommand(
    arguments: List<String>,
    root: Path,
    evidence: Path,
    label: String,
    timeout: Duration,
): ConsumerCommandResult {
    val result = ConsumerFixture.command(arguments, root, evidence, label, timeout)
    check(result.exitCode == 0) { "$label failed with exit ${result.exitCode}: ${evidence.resolve("$label.log")}" }
    return result
}
