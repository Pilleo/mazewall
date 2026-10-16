package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

@Tag("consumer-bootstrap")
class KoogBootstrapTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
    fun `real disposable producer refuses stale receipts excluded compilation and stage failures`() {
        val original = Path.of(System.getProperty("consumerWorkspace"))
        SharedVerificationLock.acquire(SharedVerificationLock.resolve(original, System.getProperty("verificationLock"))).use {
            val inputs = ConsumerFixture.snapshot(original)
            try {
                val root = ConsumerFixture.checkout(original)
                val evidence = Files.createDirectory(root.resolve("bootstrap-evidence"))
                println("bootstrap_test_evidence=$evidence")
                var sequence = 0
                val wrapper = listOf("bash", root.resolve("scripts/koog-build.sh").toString(), "--console=plain", "--max-workers=1")
                val direct = listOf(root.resolve("gradlew").toString(), "--console=plain", "--max-workers=1", "--no-configuration-cache", "-PincludeKoogIntegration=true")

                fun run(
                    label: String,
                    arguments: List<String>,
                    expected: Int = 0,
                    message: String? = null,
                    timeout: Duration = Duration.ofMinutes(4),
                ): String {
                    val result = ConsumerFixture.command(arguments, root, evidence, "%02d-%s".format(++sequence, label), timeout)
                    val text = result.stdout + result.stderr
                    assertEquals(expected, result.exitCode, "$label: $evidence\n$text")
                    if (message != null) assertTrue(message in text, "$label missing $message: $evidence\n$text")
                    println("BOOTSTRAP_CASE_OK $label status=${result.exitCode}")
                    return text
                }
                val receipt = root.resolve("build/portal-codegen/plugin-classpath.properties")
                val plugin = root.resolve("portals/codegen/build/libs/codegen-0.1.0-SNAPSHOT.jar")
                val selected = listOf("--", ":demos:koog-contained-tool:portalCheck")
                // Cold cloned buildSrc executes the mandatory TestKit gates before root configuration.
                val default =
                    run("default-core", listOf(root.resolve("gradlew").toString(), "--console=plain", "--max-workers=1", "--no-configuration-cache", "projects"), timeout = Duration.ofMinutes(10))
                val projects = Regex("(?m)^project '(:[^']+)' - ").findAll(default.substringAfterLast("Project locations:\n")).map { it.groupValues[1] }.toSet()
                assertTrue(setOf(":enforcer", ":platform").all { it in projects }, "Missing actual projects listing")
                assertFalse(":integrations:koog" in projects || ":demos:koog-contained-tool" in projects)
                run("normal-producer-and-demo", wrapper + selected)
                assertTrue("source.buildSrc/build.gradle.kts=" in Files.readString(receipt))
                assertTrue("source.profiler/src/main/" in Files.readString(receipt))
                val resource = root.resolve("portals/codegen/src/main/resources/task10-freshness-probe.txt")
                Files.createDirectories(resource.parent)
                Files.writeString(resource, "actual producer resource v1\n", CREATE_NEW)
                run("added-source-rejected", direct + "help", 1, "Stale Portal plugin producer sources")
                run("added-source-refreshed", wrapper + selected)
                JarFile(plugin.toFile()).use { jar -> assertArrayEquals(Files.readAllBytes(resource), jar.getInputStream(jar.getJarEntry(resource.fileName.toString())).readAllBytes()) }
                Files.writeString(resource, "actual producer resource v2\n")
                run("modified-source-rejected", direct + "help", 1, "Stale Portal plugin producer sources")
                Files.delete(resource)
                run("removed-source-rejected", direct + "help", 1, "Stale Portal plugin producer sources")
                run("source-removal-refreshed", wrapper + selected)
                JarFile(plugin.toFile()).use { assertEquals(null, it.getJarEntry(resource.fileName.toString())) }
                var validReceipt = Files.readAllBytes(receipt)
                Files.writeString(receipt, String(validReceipt).replace("version=0.1.0-SNAPSHOT", "version=0.1.0-stale"))
                run("version-rejected", direct + "help", 1, "Stale Portal plugin producer version")
                Files.writeString(receipt, String(validReceipt).replace(root.toString(), "/different/worktree"))
                run("other-worktree-rejected", direct + "help", 1, "Portal plugin receipt belongs")
                Files.delete(receipt)
                run("missing-receipt-rejected", direct + "help", 1, "Build the actual Portal plugin first")
                Files.write(receipt, validReceipt, CREATE_NEW)
                val versionOptions =
                    listOf("-I", root.resolve("tests/consumers/stage.init.gradle").toString(), "-PconsumerVersion=0.1.0-task10-freshness", "-PconsumerRepository=${root.resolve("repository")}")
                run("changed-build-version-rejected", direct + versionOptions + "help", 1, "Stale Portal plugin producer version")
                run("changed-build-version-refreshed", wrapper + versionOptions + selected)
                assertTrue("version=0.1.0-task10-freshness" in Files.readString(receipt))
                run("default-build-version-refreshed", wrapper + selected)
                validReceipt = Files.readAllBytes(receipt)
                val validPlugin = Files.readAllBytes(plugin)
                Files.delete(plugin)
                run("missing-actual-jar-rejected", direct + "help", 1, "Stale/missing Portal plugin artifact")
                Files.write(plugin, validPlugin.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }, CREATE_NEW)
                run("changed-actual-jar-rejected", direct + "help", 1, "Stale/missing Portal plugin artifact")
                Files.write(plugin, validPlugin)
                val failure = Files.writeString(
                    evidence.resolve("producer-failure.init.gradle"),
                    "settingsEvaluated { settings -> if (settings.startParameter.projectProperties['koogBootstrap'] == 'true') throw new GradleException('TASK10_ACTUAL_PRODUCER_FAILURE') }\n",
                    CREATE_NEW,
                )
                val failed = run("producer-failure-propagates", wrapper + listOf("-I", failure.toString(), "--", "help"), 1, "TASK10_ACTUAL_PRODUCER_FAILURE")
                assertFalse("KOOG_STAGE selected" in failed)
                assertArrayEquals(validReceipt, Files.readAllBytes(receipt))
                assertTrue("KOOG_STAGE selected" in run("selected-failure-propagates", wrapper + listOf("--", ":demos:koog-contained-tool:task10MissingTask"), 1, "task10MissingTask"))
                run(
                    "configuration-cache-rejected",
                    listOf(root.resolve("gradlew").toString(), "--console=plain", "--configuration-cache", "-PincludeKoogIntegration=true", "help"),
                    1,
                    "Koog source bootstrap requires uncached receipt validation",
                )
                validReceipt = Files.readAllBytes(receipt)
                val producer = direct + listOf("-PkoogBootstrap=true", ":portals:codegen:exportPluginClasspath")
                val source = root.resolve("portals/codegen/src/main/kotlin/Task10ExcludedCompileProbe.kt")
                Files.writeString(source, "package io.mazewall.portal.codegen\n\nclass Task10ExcludedCompileProbe\n", CREATE_NEW)
                val excluded = ConsumerFixture.command(producer + "--exclude-task=:portals:codegen:compileKotlin", root, evidence, "direct-excluded")
                assertTrue(excluded.exitCode != 0)
                assertTrue("Portal plugin export forbids task exclusions" in excluded.stdout + excluded.stderr)
                assertArrayEquals(validReceipt, Files.readAllBytes(receipt))
                JarFile(plugin.toFile()).use { assertEquals(null, it.getJarEntry("io/mazewall/portal/codegen/Task10ExcludedCompileProbe.class")) }
                val skip = Files.writeString(
                    evidence.resolve("skip-producer.init.gradle"),
                    "allprojects { tasks.configureEach { if (path == ':portals:codegen:compileKotlin') onlyIf { false } } }\n",
                    CREATE_NEW,
                )
                run("actual-onlyif-skip", producer + listOf("-I", skip.toString()), 1, "producer task did not execute")
                assertArrayEquals(validReceipt, Files.readAllBytes(receipt))
                for ((index, option) in listOf(
                        listOf("--exclude-task=:portals:codegen:compileKotlin"),
                        listOf("-x", ":portals:codegen:compileKotlin"),
                        listOf("-x:portals:codegen:compileKotlin"),
                        listOf("--exclude-task", ":portals:codegen:jar"),
                    ).withIndex()) {
                    for (shared in listOf(true, false)) {
                        val text = run("wrapper-exclusion-$index-$shared", wrapper + if (shared) option + listOf("--", "help") else listOf("--", "help") + option, 64)
                        assertFalse("KOOG_STAGE selected" in text)
                        assertArrayEquals(validReceipt, Files.readAllBytes(receipt))
                    }
                }
                Files.delete(source)
                run("final-real-generator", wrapper + listOf("--", ":demos:koog-contained-tool:jar", ":demos:koog-contained-tool:portalCheck"))
                val hostJar = root.resolve("demos/koog-contained-tool/build/libs/koog-contained-tool-0.1.0-SNAPSHOT.jar")
                KoogWorkerPackage.verify(hostJar, KoogPackageKind.PRODUCTION)
                run("native-fixture-real-generator", wrapper + listOf("--", "-PkoogNativeFixtures=true", ":demos:koog-contained-tool:jar", ":demos:koog-contained-tool:portalCheck"))
                KoogWorkerPackage.verify(hostJar, KoogPackageKind.NATIVE_FIXTURE)
            } finally {
                assertEquals(inputs, ConsumerFixture.snapshot(original), "Disposable producer tests changed the original checkout")
            }
        }
    }
}
