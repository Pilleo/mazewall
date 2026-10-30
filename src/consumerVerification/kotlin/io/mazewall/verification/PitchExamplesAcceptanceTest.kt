package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.time.Duration
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class PitchExamplesAcceptanceTest {
    @TempDir private lateinit var root: Path

    @Test fun `package inspection refuses absent or source substituted publication bytes`() {
        val repository = Files.createDirectory(root.resolve("repo"))
        val version = "0.1.0-integration.123456789abc"
        val modules = listOf("platform", "enforcer", "mazewall-spring-boot", "mazewall-spring-boot-starter")
        for (module in modules) {
            val file = repository.resolve("io/mazewall/$module/$version/$module-$version.jar")
            Files.createDirectories(file.parent)
            Files.writeString(file, "published-$module")
        }

        fun fixture(
            name: String,
            corrupt: Boolean,
            missing: Boolean,
        ): Path {
            val jar = root.resolve("$name.jar")
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Main-Class", "org.springframework.boot.loader.launch.JarLauncher")
                mainAttributes.putValue("Spring-Boot-Version", "4.1.1")
            }
            JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
                for (module in modules.filterNot { missing && it == "enforcer" }) {
                    out.putNextEntry(JarEntry("BOOT-INF/lib/$module-$version.jar"))
                    out.write(if (corrupt && module == "enforcer") byteArrayOf(0) else Files.readAllBytes(repository.resolve("io/mazewall/$module/$version/$module-$version.jar")))
                    out.closeEntry()
                }
            }
            return jar
        }
        PitchExamplesAcceptance.inspectSpring(fixture("valid", false, false), repository, version)
        assertThrows(IllegalStateException::class.java) { PitchExamplesAcceptance.inspectSpring(fixture("corrupt", true, false), repository, version) }
        assertThrows(IllegalStateException::class.java) { PitchExamplesAcceptance.inspectSpring(fixture("missing", false, true), repository, version) }
    }

    @Test fun `test reports must show exact cases and never skipped failed or absent observations`() {
        val file = root.resolve("tests.xml")

        fun report(
            tests: Int,
            skipped: Int,
            failures: Int,
        ): String = "<testsuite tests=\"$tests\" errors=\"0\" failures=\"$failures\" skipped=\"$skipped\">" + "<testcase/>".repeat(tests) + "</testsuite>"
        for (bad in listOf(report(0, 0, 0), report(2, 1, 0), report(2, 0, 1), "<testsuite/>")) {
            Files.writeString(file, bad)
            assertThrows(IllegalStateException::class.java) { PitchExamplesAcceptance.requireTestReport(file, 2) }
        }
        Files.writeString(file, report(2, 0, 0))
        PitchExamplesAcceptance.requireTestReport(file, 2)
    }
}

internal object PitchExamplesAcceptance {
    fun inspectSpring(
        jar: Path,
        repository: Path,
        version: String,
    ) {
        JarFile(jar.toFile()).use { archive ->
            check(archive.manifest.mainAttributes.getValue("Main-Class") == "org.springframework.boot.loader.launch.JarLauncher")
            check(archive.manifest.mainAttributes.getValue("Spring-Boot-Version") == "4.1.1")
            val names = archive
                .entries()
                .asSequence()
                .map { it.name }
                .toList()
            check(names.size == names.distinct().size)
            check(names.none { it.startsWith("BOOT-INF/classes/demo/report/HeldReport") }) { "Test-only held-cleanup fixture leaked into normal application" }
            for (module in listOf("platform", "enforcer", "mazewall-spring-boot", "mazewall-spring-boot-starter")) {
                val expected = "BOOT-INF/lib/$module-$version.jar"
                val moduleJar = Regex("BOOT-INF/lib/${Regex.escape(module)}-[0-9].*\\.jar")
                check(names.filter(moduleJar::matches) == listOf(expected)) { "Missing, duplicate or wrong-version publication: $module" }
                val nested = archive.getInputStream(archive.getJarEntry(expected)).use { it.readAllBytes() }
                check(nested.contentEquals(Files.readAllBytes(repository.resolve("io/mazewall/$module/$version/$module-$version.jar")))) { "Source-substituted nested publication: $module" }
            }
        }
    }

    fun requireTestReport(
        path: Path,
        expected: Int,
    ) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val report = factory.newDocumentBuilder().parse(path.toFile()).documentElement
        check(
            report.tagName == "testsuite" &&
            report.getAttribute("tests") == expected.toString() &&
            report.getElementsByTagName("testcase").length == expected &&
            listOf("failures", "errors", "skipped").all { report.getAttribute(it) == "0" },
        ) { "Required actual test observations missing: $path" }
    }

    fun run(
        root: Path,
        repository: Path,
        version: String,
        evidence: Path,
    ) {
        val copies = Files.createTempDirectory("mazewall-pitch-consumers-")
        for (name in listOf("koog-document-agent", "spring-report-api")) {
            val source = root.resolve("demos/$name")
            val copy = Files.createDirectory(copies.resolve(name))
            Files.walk(source).use { paths ->
                paths.filter { path -> source.relativize(path).none { it.toString() in setOf("build", ".gradle", ".kotlin") } }.forEach { path ->
                    val target = copy.resolve(source.relativize(path))
                    check(!Files.isSymbolicLink(path)) { "Consumer source alias: $path" }
                    if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target, COPY_ATTRIBUTES)
                }
            }
            val metadata = copy.resolve("gradle/verification-metadata.xml")
            val modules = if (name == "koog-document-agent") {
                listOf("platform", "enforcer", "runtime", "koog", "worker", "codegen", "profiler")
            } else {
                listOf("platform", "enforcer", "mazewall-spring-boot", "mazewall-spring-boot-starter")
            }
            var pinned = withOptionalPublicationChecksums(Files.readString(metadata), repository, version, modules)
            if (name == "koog-document-agent") {
                val artifact = "io.mazewall.portal.codegen.gradle.plugin-$version.pom"
                val marker = repository.resolve("io/mazewall/portal/codegen/io.mazewall.portal.codegen.gradle.plugin/$version/$artifact")
                val component = "<component group=\"io.mazewall.portal.codegen\" name=\"io.mazewall.portal.codegen.gradle.plugin\" version=\"$version\">" +
                    "<artifact name=\"$artifact\"><sha256 value=\"${BootFixture.hash(marker)}\" origin=\"Exact committed producer plugin marker\"/></artifact></component>"
                pinned = pinned.replace("</components>", "$component</components>")
            }
            Files.writeString(metadata, pinned)
            val historicalLocks = Files.walk(copy).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "gradle.lockfile" }.toList().associateWith(Files::readAllBytes)
            }
            val command = listOf(
                copy.resolve("gradlew").toString(),
                "-p",
                copy.toString(),
                "--console=plain",
                "--max-workers=1",
                "-PconsumerVersion=$version",
                "-PconsumerRepository=$repository",
            )
            val tasks = if (name == "koog-document-agent") {
                listOf("test", "nativeTest", "installDist", "distZip", "sourceZip")
            } else {
                listOf("test", "nativeTest", "bootJar", "evaluationZip", "sourceZip")
            }
            for ((label, flags) in listOf(
                "locks" to listOf("--write-locks", "--no-configuration-cache"),
                "strict" to listOf("--configuration-cache", "--configuration-cache-problems=fail"),
                "cache-reuse" to listOf("--configuration-cache", "--configuration-cache-problems=fail"),
            )) {
                val seed = if (label == "locks" && name == "koog-document-agent") listOf(":dependencies", ":boundary-fixture:dependencies") else emptyList()
                val result = ConsumerFixture.command(command + seed + tasks + flags, copy, evidence, "$name-$label", Duration.ofMinutes(8))
                check(result.exitCode == 0) { "Copied $name $label failed: ${result.stdout.takeLast(6000)}${result.stderr.takeLast(6000)}" }
                if (label == "cache-reuse") check("Configuration cache entry reused" in result.stdout)
                check(Files.readString(metadata) == pinned) { "Copied consumer changed dependency approvals" }
                check(historicalLocks.all { (path, bytes) -> Files.readAllBytes(path).contentEquals(bytes) }) { "New-coordinate build changed historical locks" }
            }
            val audit = ConsumerFixture.command(command + listOf("verifyPublishedClosure", "--no-configuration-cache"), copy, evidence, "$name-closure", Duration.ofMinutes(8))
            check(audit.exitCode == 0 && "PUBLISHED io.mazewall:" in audit.stdout) { "Copied consumer closure audit failed: ${audit.stdout}${audit.stderr}" }
            if (name == "koog-document-agent") {
                requireTestReport(copy.resolve("build/test-results/test/TEST-demo.koog.DocumentAgentTest.xml"), 3)
                requireTestReport(copy.resolve("build/test-results/test/TEST-demo.koog.ScriptedModelTest.xml"), 5)
                requireTestReport(copy.resolve("build/test-results/test/TEST-demo.koog.PortalDocumentAgentTest.xml"), 3)
                requireTestReport(copy.resolve("build/test-results/nativeTest/TEST-demo.koog.DocumentAgentNativeTest.xml"), 3)
                val lib = copy.resolve("build/install/koog-document-agent/lib")
                for (module in listOf("platform", "enforcer", "runtime", "koog")) {
                    check(Files.readAllBytes(lib.resolve("$module-$version.jar")).contentEquals(Files.readAllBytes(repository.resolve("io/mazewall/$module/$version/$module-$version.jar")))) {
                        "Substituted Koog publication: $module"
                    }
                }
                Files.list(lib).use { files -> check(files.noneMatch { "boundary" in it.fileName.toString() || "profiler" in it.fileName.toString() || "native-probe" in it.fileName.toString() }) }
                JarFile(lib.resolve("koog-document-agent.jar").toFile()).use { host ->
                    check(host.getInputStream(host.getJarEntry("META-INF/mazewall/portal/services.index")).bufferedReader().use { it.readText() } == "document-outline\n")
                    check(host.entries().asSequence().none { it.name.startsWith("demo/boundary/") || it.name == "demo/documents/MarkdownOutline.class" })
                }
            } else {
                inspectSpring(copy.resolve("build/libs/application.jar"), repository, version)
                requireTestReport(copy.resolve("build/test-results/test/TEST-demo.report.ReportServiceTest.xml"), 2)
                requireTestReport(copy.resolve("build/test-results/test/TEST-demo.report.ReportContextTest.xml"), 3)
                requireTestReport(copy.resolve("build/test-results/test/TEST-demo.report.ReportListenerTest.xml"), 5)
                requireTestReport(copy.resolve("build/test-results/nativeTest/TEST-demo.report.ReportHttpTest.xml"), 8)
            }
            val measurement = ConsumerFixture.command(
                listOf(
                    "python3",
                    root.resolve("benchmarks/integration-boundaries/pitch/measure.py").toString(),
                if (name == "koog-document-agent") "koog" else "spring",
                    copy.toString(),
                ),
                    copy,
                evidence,
                "$name-measurements",
                Duration.ofMinutes(8),
            )
            check(measurement.exitCode == 0) { "Source-bound application measurements failed: ${measurement.stderr}${measurement.stdout.takeLast(6000)}" }
            check(measurement.stdout.lineSequence().count { it.startsWith("{\"schema\":1,") } == if (name == "koog-document-agent") 12 else 3) { "Missing exact application measurement forks" }
            println("PITCH_APPLICATION_MEASUREMENTS_OK name=$name forksPerMode=3 warmups=20 serialSamples=100 scope=local-only")
            println("PITCH_COPIED_CONSUMER_OK name=$name version=$version native=executed packaging=published-only cache=reused")
        }
    }
}
