package io.mazewall.verification

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.jar.JarFile

internal object BootFixture {
    private val nativeEvidence: Path by lazy {
        val path = Path.of(required("consumerEvidence"))
        require(path.isAbsolute) { "consumerEvidence must be an absolute new directory" }
        Files.createDirectories(path.parent)
        Files.createDirectory(path)
    }

    fun evidence(name: String): Path = Files.createDirectory(nativeEvidence.resolve(name))

    fun required(name: String): String = System.getProperty(name)?.takeIf(String::isNotBlank) ?: error("Missing required -P$name")

    fun deployment(
        jar: Path,
        evidence: Path,
    ): Path {
        val root = Files.createTempDirectory(evidence, "deployment-")
        Files.write(root.resolve("application.jar"), Files.readAllBytes(jar), CREATE_NEW)
        Files.writeString(root.resolve("template.txt"), "Reviewed quarterly template", CREATE_NEW)
        Files.writeString(root.resolve("sibling.txt"), "Unapproved sibling secret", CREATE_NEW)
        Files.createDirectory(root.resolve("reports"))
        Files.createFile(root.resolve("reports/quarterly.txt"))
        return root
    }

    fun inspectJar(
        jar: Path,
        repository: Path,
        version: String,
    ): JsonObject {
        require(repository.isAbsolute && Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?[-.A-Za-z0-9]*").matches(version)) { "Pinned absolute publication repository/version required" }
        check(Files.isRegularFile(jar)) { "Missing actual finished Boot executable: $jar" }
        return JarFile(jar.toFile()).use { archive ->
            check(archive.manifest.mainAttributes.getValue("Main-Class") == "org.springframework.boot.loader.launch.JarLauncher") { "Unexpected Boot launcher" }
            check(archive.manifest.mainAttributes.getValue("Spring-Boot-Version") == "4.1.1") { "Unexpected Boot version" }
            val names = archive
                .entries()
                .asSequence()
                .map { it.name }
                .toList()
            check(names.distinct().size == names.size) { "Duplicate finished-JAR entries" }
            check("BOOT-INF/classes/demo/boot/ReportService.class" in names) { "Missing actual report fixture class" }
            check(names.none { "spring-security" in it }) { "This fixture advertises Security absence" }
            buildJsonObject {
                for (module in listOf("platform", "enforcer", "mazewall-spring-boot", "mazewall-spring-boot-starter")) {
                    val publication = repository.resolve("io/mazewall/$module/$version/$module-$version.jar")
                    check(Files.isRegularFile(publication)) { "Missing actual Maven publication: $publication" }
                    val entry = archive.getJarEntry("BOOT-INF/lib/$module-$version.jar") ?: error("Missing nested publication: $module")
                    val actual = archive.getInputStream(entry).use { it.readAllBytes() }
                    check(hash(actual) == hash(publication)) { "Source substitution for $module" }
                    put(
                        module,
                        buildJsonObject {
                        put("path", publication.toRealPath().toString())
                        put("sha256", hash(publication))
                    },
                    )
                }
            }
        }
    }

    fun sourceIdentity(
        root: Path,
        revision: String,
        evidence: Path,
    ): JsonObject {
        require(Regex("[0-9a-f]{40}").matches(revision)) { "Full pinned producer commit required" }
        val receipts = Files.createDirectory(evidence.resolve("source-${System.nanoTime()}"))
        val scopes =
            listOf(
                "platform",
                "enforcer",
                "integrations/spring/mazewall-spring-boot",
                "integrations/spring/mazewall-spring-boot-starter",
                "buildSrc",
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradle",
                "gradle.properties",
            )

        fun git(
            label: String,
            vararg args: String,
        ): String {
            val result = ConsumerFixture.command(listOf("git") + args, root, receipts, label)
            check(result.exitCode == 0) { "Producer source differs from pinned revision ($label): ${result.stdout}${result.stderr}" }
            return result.stdout
        }
        val actualRevision = git("revision", "rev-parse", "$revision^{commit}").trim()
        git("producer-diff", * (listOf("diff", "--exit-code", actualRevision, "--") + scopes).toTypedArray())
        val unknown = git("producer-unknown", *(listOf("ls-files", "--others", "--exclude-standard", "--") + scopes).toTypedArray())
        check(unknown.isBlank()) { "Unidentified producer source inputs: $unknown" }
        val tracked = git("producer-inputs", *(listOf("ls-files", "-z", "--") + scopes).toTypedArray()).split('\u0000').filter(String::isNotEmpty)
        val producer = identities(root, tracked.map(root::resolve).filter(Files::isRegularFile))
        val demo = root.resolve("demos/spring-boot-sandbox")
        val harness = root.resolve("src/consumerVerification/kotlin")
        val applicationFiles = listOf(demo.resolve("build.gradle"), demo.resolve("settings.gradle"), root.resolve("tests/consumers/spring-boot/optional-modules.init.gradle")) +
            Files.walk(demo.resolve("src")).use { it.filter { path -> path.toString().endsWith(".java") }.toList() } +
            Files.walk(harness).use { it.filter { path -> path.toString().endsWith(".kt") }.toList() } +
            listOf(root.resolve("src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt")).filter(Files::isRegularFile)
        val application = identities(root, applicationFiles)
        return buildJsonObject {
            put("producerRevision", actualRevision)
            put("producerInputs", producer)
            put("producerSourceSha256", hash(producer.toString().toByteArray()))
            put("demoAndHarnessInputs", application)
            put("demoAndHarnessSourceSha256", hash(application.toString().toByteArray()))
        }
    }

    private fun identities(
        root: Path,
        paths: List<Path>,
    ): JsonObject =
        buildJsonObject {
        for (path in paths.sorted()) put(root.relativize(path).toString(), hash(path))
    }

    fun hash(path: Path): String = hash(Files.readAllBytes(path))

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
