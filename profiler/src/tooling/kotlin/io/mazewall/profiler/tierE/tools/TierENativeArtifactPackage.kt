package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Validate restored native build products before privileged use; CTest results are never cached. */
public object TierENativeArtifactPackage {
    public data class Expected(
        val imageId: String,
        val buildType: String,
        val usdt: Boolean,
        val compiler: String,
        val java: String,
        val cmake: String,
        val architecture: String,
    )

    public data class Verified(
        val sourceDigest: String,
        val agentSha256: String,
        val workloadClassSha256: String,
    )

    /** Materialize immutable cached bytes into this run's fresh CMake/CTest directory. */
    public fun installCommands(runner: String): List<List<String>> =
        listOf(
        listOf("exec", runner, "cp", "-a", "/native-package/libmazewall_stack_agent.so", "/workspace/profiler-native/build/"),
        listOf("exec", runner, "cp", "-a", "/native-package/test-binaries/.", "/workspace/profiler-native/build/"),
        listOf("exec", runner, "mkdir", "-p", "/workspace/profiler-native/build/agent-test-classes"),
        listOf("exec", runner, "cp", "-a", "/native-package/agent-test-classes/.", "/workspace/profiler-native/build/agent-test-classes/"),
    )

    public fun sourceDigest(native: Path): String {
        require(Files.isDirectory(native, NOFOLLOW_LINKS)) { "Native source directory unavailable" }
        val sources = Files.walk(native).use { paths ->
            paths
                .filter { file ->
                val name = native.relativize(file).toString().replace('\\', '/')
                name == "CMakeLists.txt" || name.startsWith("src/") || name.startsWith("include/") || name.startsWith("tests/")
            }.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }
                .toList()
        }
        require(sources.isNotEmpty() && sources.all { Files.isRegularFile(it, NOFOLLOW_LINKS) }) {
            "Native sources contain missing, special or symlinked inputs"
        }
        val sha = MessageDigest.getInstance("SHA-256")
        for (file in sources.sortedBy { native.relativize(it).toString().replace('\\', '/') }) {
            sha.update(
                native
                .relativize(file)
                .toString()
                .replace('\\', '/')
                .toByteArray(),
            )
            sha.update(0)
            sha.update(Files.readAllBytes(file))
            sha.update(0)
        }
        return hex(sha.digest())
    }

    public fun verify(
        packageRoot: Path,
        nativeSource: Path,
        expected: Expected,
    ): Verified {
        require(Files.isDirectory(packageRoot, NOFOLLOW_LINKS)) { "Native package directory missing" }
        val manifest = packageRoot.resolve("manifest.json")
        require(Files.isRegularFile(manifest, NOFOLLOW_LINKS)) { "Native package manifest missing or symlinked" }
        val document = Json.parseToJsonElement(Files.readString(manifest)).jsonObject
        require(
            document.keys == setOf(
                "schema_version",
                "source_digest",
                "image_id",
                "build_type",
                "usdt",
            "compiler",
                "java",
                "cmake",
                "architecture",
                "policy_eligibility",
                "files",
            ),
        ) {
            "Native package manifest fields differ"
        }
        val source = sourceDigest(nativeSource)
        val fields = mapOf(
            "source_digest" to source,
            "image_id" to expected.imageId,
            "build_type" to expected.buildType,
            "usdt" to expected.usdt.toString(),
            "compiler" to expected.compiler,
            "java" to expected.java,
            "cmake" to expected.cmake,
            "architecture" to expected.architecture,
        )
        require(
            document["schema_version"]?.jsonPrimitive?.content == "1" &&
            document["policy_eligibility"]?.jsonPrimitive?.content == "false" &&
            fields.all { (name, value) -> document[name]?.jsonPrimitive?.content == value },
        ) {
            "Native package source, toolchain, image, build mode or policy provenance differs"
        }
        val files = document.getValue("files").jsonObject
        require(
            files.isNotEmpty() &&
            files.keys.containsAll(
                setOf(
                    "libmazewall_stack_agent.so",
            "agent-test-classes/AgentWorkload.class",
                    "agent-test-classes/redefinition-agent.jar",
                ),
            ) &&
            files.keys.any { it.startsWith("test-binaries/") },
        ) { "Native package lacks test or workload binaries" }
        for ((name, value) in files) {
            val location = Path.of(name)
            require(!location.isAbsolute && location.normalize() == location && !name.startsWith(".")) {
                "Native package path escapes its directory"
            }
            val file = packageRoot.resolve(location)
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && hash(file) == value.jsonPrimitive.content) {
                "Native package artifact changed: $name"
            }
            if (name.startsWith("test-binaries/")) require(Files.isExecutable(file)) { "Native test fixture lost executable mode: $name" }
        }
        val inventory = Files.walk(packageRoot).use { paths ->
            paths
                .filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }
                .map { packageRoot.relativize(it).toString().replace('\\', '/') }
                .toList()
                .toSet()
        }
        require(inventory == files.keys + "manifest.json") { "Native package contains unexpected or missing files" }
        return Verified(
            source,
            hash(packageRoot.resolve("libmazewall_stack_agent.so")),
            hash(packageRoot.resolve("agent-test-classes/AgentWorkload.class")),
        )
    }

    private fun hash(file: Path): String = hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
