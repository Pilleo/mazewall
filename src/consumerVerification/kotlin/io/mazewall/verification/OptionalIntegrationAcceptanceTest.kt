package io.mazewall.verification

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal const val KOOG_CONTAINMENT_SUMMARY = "KOOG_CONTAINMENT_V1_OK read=denied write=denied symlink=denied network=denied process=denied controls=pass retirement=pass"

internal fun requireKoogContainmentSummary(output: String): String {
    val summaries = output.lineSequence().filter { it.startsWith("KOOG_CONTAINMENT_V1_") }.toList()
    check(summaries.size == 1 && summaries.single() == KOOG_CONTAINMENT_SUMMARY) {
        "Expected exactly one complete Koog containment summary; found: $summaries"
    }
    return summaries.single()
}

internal const val KOOG_THREAD_SUMMARY =
    "KOOG_THREAD_V1_OK registry=upstream worker=platform fs=isolated socket=denied caller=unchanged virtual=true unconfined=true " +
        "validation=pass overload=rejected cancellation=cooperative ownership=checked retirement=complete fallback=false replay=false"

internal fun requireKoogThreadSummary(output: String): String {
    val summaries = output.lineSequence().filter { it.startsWith("KOOG_THREAD_V1_") }.toList()
    check(summaries.size == 1 && summaries.single() == KOOG_THREAD_SUMMARY) {
        "Expected exactly one complete Koog thread summary; found: $summaries"
    }
    return summaries.single()
}

internal fun requireKoogSpringSummaries(output: String): Set<String> {
    val contract = "registry=upstream binding=approved worker=platform identity=restored jdbc=commit-and-rollback pre=denied post=rolled-back " +
        "business=preserved callerTransaction=rejected fs=denied cancellation=cooperative retirement=complete fallback=false replay=false"
    val expected = setOf("KOOG_SPRING_V1_OK caller=platform $contract", "KOOG_SPRING_V1_OK caller=virtual $contract")
    val summaries = output.lineSequence().filter { it.startsWith("KOOG_SPRING_V1_") }.toList()
    check(summaries.size == 2 && summaries.toSet() == expected) { "Expected exactly one complete platform and virtual Koog/Spring summary; found: $summaries" }
    return summaries.toSet()
}

internal fun withOptionalPublicationChecksums(
    metadata: String,
    repository: Path,
    version: String,
    modules: List<String> = listOf("platform", "enforcer", "runtime", "koog", "mazewall-spring-boot", "mazewall-spring-boot-starter"),
): String {
    require(Regex("0\\.1\\.0-integration\\.[a-f0-9]{12}").matches(version)) { "Exact producer revision coordinate required" }
    check("version=\"$version\"" !in metadata && metadata.split("</components>").size == 2) { "New coordinate and intact verification metadata required" }
    val additions = buildString {
        for (module in modules) {
            appendLine("<component group=\"io.mazewall\" name=\"$module\" version=\"$version\">")
            for (extension in listOf("jar", "pom", "module")) {
                val name = "$module-$version.$extension"
                val artifact = repository.resolve("io/mazewall/$module/$version/$name")
                require(Files.isRegularFile(artifact)) { "Missing exact publication bytes: $artifact" }
                val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)).joinToString("") { "%02x".format(it) }
                appendLine("<artifact name=\"$name\"><sha256 value=\"$sha\" origin=\"Exact committed producer publication\"/></artifact>")
            }
            appendLine("</component>")
        }
    }
    // Add only this producer's fixed coordinates; all historical and external approvals stay byte-identical.
    return metadata.replace("</components>", additions + "</components>")
}

internal fun requireKoogAgentSummaries(
    threadOutput: String,
    portalOutput: String,
): Set<String> {
    val expected = listOf(
        listOf(
            "KOOG_AGENT_V1_OK boundary=thread loop=real validation=rejected denial=preserved overload=rejected cancellation=cooperative deadline=propagated retirement=complete replay=false",
            "KOOG_AGENT_SEQUENCE_V1_OK boundary=thread history=ordered turns=3 deniedThenAllowed=pass " +
                "repeated=two-explicit-calls queue=bounded queuedCancellation=not-started retirement=complete",
        ),
        listOf(
            "KOOG_AGENT_V1_OK boundary=portal loop=real validation=rejected denial=preserved unknown=terminal cancellation=cooperative deadline=propagated retirement=complete replay=false",
            "KOOG_AGENT_SEQUENCE_V1_OK boundary=portal history=ordered turns=3 deniedThenAllowed=pass " +
                "repeated=two-explicit-calls unknown=terminal queue=bounded queuedCancellation=not-started retirement=complete",
        ),
    )
    for ((output, summaries) in listOf(threadOutput, portalOutput).zip(expected)) {
        for (summary in summaries) {
            val prefix = summary.substringBefore("_OK") + "_"
            val found = output.lineSequence().filter { it.startsWith(prefix) }.toList()
            check(found == listOf(summary)) { "Expected exactly one complete agent summary: $summary; found $found" }
        }
    }
    return expected.flatten().toSet()
}

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
                        ":demos:cli-demo:test",
                        "--tests",
                        "*OwnedDemoRuntimeAwaitTest",
                        "--tests",
                        "*WorkspaceExampleTest",
                        ":demos:cli-demo:runWorkspaceExample",
                        ":demos:koog-contained-tool:runPortalExample",
                        ":demos:koog-contained-tool:runThreadExample",
                    ":demos:koog-contained-tool:portalCheck",
                ) +
                    listOf(
                    ":platform",
                         ":enforcer",
                         ":profiler",
                         ":portals:runtime",
                         ":portals:worker",
                         ":portals:codegen",
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
                val koogVerification = root.resolve("tests/consumers/koog-portal/gradle/verification-metadata.xml")
                val koogPinned = withOptionalPublicationChecksums(
                    Files.readString(koogVerification),
                    repository,
                    version,
                    listOf("platform", "enforcer", "runtime", "koog", "koog-contained-tool", "denied-api", "service-api"),
                )
                Files.writeString(koogVerification, koogPinned)
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
                println(requireKoogContainmentSummary(native))
                val threadNative = run("strict-fresh-native-koog-thread", koog + "verifyFreshThreadNative")
                println(requireKoogThreadSummary(threadNative))
                val threadAgent = run("strict-fresh-thread-agent", koog + "verifyFreshThreadAgent")
                val portalAgent = run("strict-fresh-portal-agent", koog + "verifyFreshPortalAgent")
                requireKoogAgentSummaries(threadAgent, portalAgent).forEach(::println)
                check(Files.readString(koogVerification) == koogPinned) { "Agent qualification changed dependency approvals" }
                run(
                    "ordinary-jar-jdbc-security",
                    gradle +
                        listOf("-p", root.resolve("tests/consumers/spring-jdbc").toString(), "-PuseStarter=true") +
                        properties +
                        listOf("verifyNative", "verifySecurityNative", "presentationTest"),
                )
                val springConsumer = root.resolve("tests/consumers/koog-spring")
                val spring = gradle + listOf("-p", springConsumer.toString()) + properties
                val verification = springConsumer.resolve("gradle/verification-metadata.xml")
                val historicalSpringLocks = Files.walk(springConsumer.resolve("locks")).use { paths ->
                    paths.filter(Files::isRegularFile).toList().associateWith(Files::readString)
                }
                val pinned = withOptionalPublicationChecksums(Files.readString(verification), repository, version)
                Files.writeString(verification, pinned)
                run("new-koog-spring-coordinate-locks", spring + listOf("dependencies", "--write-locks"))
                val springNative = run("strict-published-koog-spring", spring + listOf("test", "verifyPublishedClosure", "verifyFreshNative"))
                requireKoogSpringSummaries(springNative).forEach(::println)
                check(Files.readString(verification) == pinned && historicalSpringLocks.all { (path, content) -> Files.readString(path) == content }) {
                    "Koog/Spring acceptance changed historical locks or verification approvals"
                }
                run(
                    "finished-boot-showcase",
                    gradle + listOf("-p", root.resolve("demos/spring-boot-sandbox").toString()) + properties + listOf("test", "bootJar", "presentationTest"),
                )
                val hostReport = root.resolve("demos/spring-boot-sandbox/build/test-results/test/TEST-demo.boot.ReportServiceTest.xml")
                check(Files.isRegularFile(hostReport)) { "Ordinary ReportService host tests did not execute" }
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                val report = factory
                    .newDocumentBuilder()
                    .parse(hostReport.toFile())
                    .documentElement
                check(
                    report.tagName == "testsuite" &&
                        report.getAttribute("name") == "demo.boot.ReportServiceTest" &&
                        report.getAttribute("tests") == "2" &&
                        report.getElementsByTagName("testcase").length == 2 &&
                        listOf("failures", "errors", "skipped").all { report.getAttribute(it) == "0" },
                ) {
                    "Both ordinary ReportService host contracts must execute without skips/failures"
                }
                println("OPTIONAL_INTEGRATION_CASE_OK ordinary-spring-report-host-tests")
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
                PitchExamplesAcceptance.run(root, repository, version, evidence)
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
