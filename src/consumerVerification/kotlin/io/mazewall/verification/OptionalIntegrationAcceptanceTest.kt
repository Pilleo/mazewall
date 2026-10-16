package io.mazewall.verification

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real source-bound publications and consumers; fixture approval is never production authority. */
@Tag("optional-integration")
class OptionalIntegrationAcceptanceTest {
    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
    fun `optional integrations ship real opaque Koog workers and finished Spring consumers`() {
        val original = Path.of(System.getProperty("consumerWorkspace"))
        SharedVerificationLock.acquire(SharedVerificationLock.resolve(original, System.getProperty("verificationLock"))).use {
            val before = ConsumerFixture.snapshot(original)
            try {
                val root = ConsumerFixture.checkout(original)
                val evidence = Files.createDirectory(root.resolve("optional-integration-evidence"))
                var sequence = 0

                fun run(
                    label: String,
                    arguments: List<String>,
                    timeout: Duration = Duration.ofMinutes(8),
                ): String {
                    val result = ConsumerFixture.command(arguments, root, evidence, "%02d-%s".format(++sequence, label), timeout)
                    val output = result.stdout + result.stderr
                    check(result.exitCode == 0) { "$label failed (${result.exitCode}); evidence=$evidence\n${output.takeLast(12_000)}" }
                    println("OPTIONAL_INTEGRATION_CASE_OK $label")
                    return output
                }

                val revision = run("producer-revision", listOf("git", "rev-parse", "HEAD")).trim()
                val producerScopes = listOf("platform", "enforcer", "profiler", "portals", "integrations", "buildSrc", "gradle", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")
                run("committed-producer", listOf("git", "diff", "--exit-code", revision, "--") + producerScopes)
                val unknown = run("identified-producer", listOf("git", "ls-files", "--others", "--exclude-standard", "--") + producerScopes)
                check(unknown.isBlank()) { "Commit the actual producer inputs before exact-publication acceptance: $unknown" }
                val version = "0.1.0-integration.${revision.take(12)}"
                val repository = root.resolve("build/optional-integration-repository")
                val properties = listOf("-PconsumerVersion=$version", "-PconsumerRepository=$repository")
                val gradle = listOf(root.resolve("gradlew").toString(), "--console=plain", "--max-workers=1", "--no-configuration-cache")
                val stage = listOf(
                    "bash",
                    root.resolve("scripts/koog-build.sh").toString(),
                    "--console=plain",
                    "--max-workers=1",
                    "-I",
                    root.resolve("tests/consumers/stage.init.gradle").toString(),
                    "-PincludeSpringIntegration=true",
                ) +
                    properties +
                    listOf(
                    "--",
                        "-PkoogNativeFixtures=true",
                    ":integrations:koog:test",
                        ":integrations:spring:mazewall-spring-boot:test",
                    ":demos:koog-contained-tool:test",
                        ":demos:koog-contained-tool:presentationTest",
                    ":demos:koog-contained-tool:portalCheck",
                ) +
                    listOf(
                    ":platform",
                        ":enforcer",
                        ":portals:runtime",
                        ":integrations:koog",
                    ":integrations:spring:mazewall-spring-boot",
                        ":integrations:spring:mazewall-spring-boot-starter",
                    ":demos:koog-contained-tool:service-api",
                        ":koog-denied-api",
                        ":demos:koog-contained-tool",
                ).map { "$it:publishAllPublicationsToConsumerBaselineRepository" }
                run("real-producer-publications", stage, Duration.ofMinutes(15))
                KoogWorkerPackage.verify(
                    repository.resolve("io/mazewall/koog-contained-tool/$version/koog-contained-tool-$version.jar"),
                    KoogPackageKind.NATIVE_FIXTURE,
                )
                val koog = gradle + listOf("-p", root.resolve("tests/consumers/koog-portal").toString()) + properties
                val historicalLocks = Files.readAllBytes(root.resolve("tests/consumers/koog-portal/gradle.lockfile"))
                val invalidVersion = ConsumerFixture.command(
                    gradle + listOf("-p", root.resolve("tests/consumers/koog-portal").toString(), "-PconsumerVersion=../escape", "-PconsumerRepository=$repository", "help", "--write-locks"),
                    root,
                    evidence,
                    "invalid-consumer-version",
                )
                check(invalidVersion.exitCode != 0 && "Pinned safe consumer version required" in invalidVersion.stdout + invalidVersion.stderr) {
                    "Malformed consumer version reached namespace selection"
                }
                run("new-coordinate-locks", koog + listOf("--write-locks", "verifyOperationRoot", "stageFreshProbe", "verifyPublishedClosure"))
                check(historicalLocks.contentEquals(Files.readAllBytes(root.resolve("tests/consumers/koog-portal/gradle.lockfile")))) {
                    "New-coordinate acceptance overwrote historical consumer locks"
                }
                val native = run("strict-fresh-native-koog", koog + "verifyFreshNative")
                check("KOOG_NATIVE_FRESH_OK" in native) { "Actual upstream Koog native probe did not finish" }
                run(
                    "ordinary-jar-jdbc-security",
                    gradle +
                        listOf("-p", root.resolve("tests/consumers/spring-jdbc").toString(), "-PuseStarter=true") +
                        properties +
                        listOf("verifyNative", "verifySecurityNative", "presentationTest"),
                )
                run(
                    "finished-boot-showcase",
                    gradle + listOf("-p", root.resolve("demos/spring-boot-sandbox").toString()) + properties + listOf("bootJar", "presentationTest"),
                )
                run(
                    "finished-boot-ownership-and-negative-controls",
                    gradle +
                        listOf("consumerNativeCheck", "--tests", "*SpringBoot*", "-PconsumerRevision=$revision") +
                        properties +
                        listOf(
                            "-PconsumerBootJar=${root.resolve("demos/spring-boot-sandbox/build/libs/report-application.jar")}",
                            "-PconsumerEvidence=${root.resolve("build/optional-boot-acceptance")}",
                        ),
                    Duration.ofMinutes(15),
                )
                println("OPTIONAL_INTEGRATION_ACCEPTANCE_PASS producer=$revision version=$version evidence=$evidence")
            } finally {
                val after = ConsumerFixture.snapshot(original)
                check(before == after) {
                    val changed = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
                    "Optional integration acceptance changed the operator checkout: $changed"
                }
            }
        }
    }
}
