package io.mazewall.verification

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Tag("packaged-consumer")
class PackagedConsumerMatrixTest {
    @Test
    fun `real pinned publications satisfy both consumer JDK and stable Kotlin rows`() {
        val root = Path.of(required("consumerWorkspace"))
        val repository = Path.of(required("consumerRepository"))
        val version = required("consumerVersion")
        val compiler = System.getProperty("consumerKotlinVersion", "2.4.20")
        require(repository.isAbsolute && Files.isDirectory(repository)) { "An existing absolute publication repository is required" }
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+[-.a-zA-Z0-9]*").matches(version)) { "Invalid pinned candidate version" }
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(compiler)) { "Consumer compiler must be a stable pinned release" }
        val evidence = Path.of(System.getProperty("consumerEvidence", root.resolve("build/consumer-evidence-${System.nanoTime()}").toString()))
        SharedVerificationLock.acquire(SharedVerificationLock.resolve(root, System.getProperty("verificationLock"))).use {
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
            command(root, listOf("git", "rev-parse", "HEAD"), evidence.resolve("source-revision.txt"))
            command(root, listOf("git", "status", "--short"), evidence.resolve("worktree-status.txt"))
            command(root, listOf(root.resolve("gradlew").toString(), "--version"), evidence.resolve("gradle-version.log"))
            val failures = mutableListOf<String>()
            for (jdk in listOf(22, 25)) {
                val arguments = listOf(
                    root.resolve("gradlew").toString(),
                    "-p",
                    root.resolve("tests/consumers").toString(),
                    "--console=plain",
                    "--no-build-cache",
                    "--no-configuration-cache",
                    "-PconsumerRepository=$repository",
                    "-PconsumerVersion=$version",
                    "-PconsumerKotlinVersion=$compiler",
                    "-PconsumerJdk=$jdk",
                ) +
                    (if (System.getProperty("consumerWriteLocks") == "true") listOf("--write-locks") else emptyList()) +
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
                    )
                Files.write(evidence.resolve("jdk$jdk.argv"), arguments, CREATE_NEW)
                val status = command(root, arguments, evidence.resolve("jdk$jdk.log"))
                if (status != 0) failures.add("JDK$jdk failed with exit $status: ${evidence.resolve("jdk$jdk.log")}")
            }
            check(failures.isEmpty()) { failures.joinToString("\n") }
        }
    }

    private fun required(name: String): String = System.getProperty(name)?.takeIf(String::isNotBlank) ?: error("Missing required -P$name")

    private fun command(
        root: Path,
        arguments: List<String>,
        log: Path,
    ): Int {
        Files.createFile(log)
        val child = ProcessBuilder(arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        try {
            check(child.waitFor(15, TimeUnit.MINUTES)) { "Consumer command timed out; evidence: $log" }
            return child.exitValue()
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                check(child.waitFor(5, TimeUnit.SECONDS)) { "Owned consumer child did not retire" }
            }
        }
    }
}
