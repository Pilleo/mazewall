package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Immutable benchmark bundle identity; no manifest may bless a partial native/runtime staging tree. */
public object TierEBenchmarkBundleManifest {
    private val digest = Regex("[a-f0-9]{64}")
    private val safePath = Regex("[A-Za-z0-9._/+:-]+")

    private fun sha(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun artifacts(bundle: Path): List<Path> {
        val native = bundle.resolve("libmazewall_stack_agent.so")
        val runtime = bundle.resolve("runtime")
        require(Files.isRegularFile(native, NOFOLLOW_LINKS) && Files.isDirectory(runtime, NOFOLLOW_LINKS)) {
            "Missing native agent or runtime directory"
        }
        val files = Files.walk(runtime).use { stream ->
            val entries = stream.toList()
            require(entries.all { Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS) }) {
                "Runtime bundle contains a symlink or special file"
            }
            entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        }
        require(files.isNotEmpty()) { "Runtime bundle has no files" }
        return (listOf(native) + files).sortedBy { bundle.relativize(it).toString() }
    }

    public fun write(
        bundle: Path,
        fingerprint: String = "",
        timings: Map<String, Long> = emptyMap(),
    ) {
        require(fingerprint.isEmpty() || digest.matches(fingerprint)) { "Invalid bundle input fingerprint" }
        val entries = artifacts(bundle)
        val checksums = entries.joinToString("\n", postfix = "\n") { file ->
            val name = bundle.relativize(file).toString().replace('\\', '/')
            require(safePath.matches(name) && ".." !in name) { "Unsafe bundle path" }
            "${sha(Files.readAllBytes(file))}  $name"
        }
        Files.writeString(bundle.resolve("checksums.sha256"), checksums)
        Files.writeString(
            bundle.resolve("manifest.json"),
            buildJsonObject {
            put("schema_version", 1)
            put("input_fingerprint", fingerprint)
            put("artifact_count", entries.size)
            put("checksums_sha256", sha(checksums.toByteArray()))
            put(
                "build_timing_ms",
                buildJsonObject {
                for (field in listOf("fingerprint", "native_configure", "native_build", "gradle_install", "staging", "total")) {
                    put(field, timings[field] ?: 0)
                }
            },
            )
        }.toString() +
            "\n",
        )
    }

    public fun verify(
        bundle: Path,
        fingerprint: String? = null,
    ): Boolean {
        val manifestFile = bundle.resolve("manifest.json")
        val checksumsFile = bundle.resolve("checksums.sha256")
        require(Files.isRegularFile(manifestFile, NOFOLLOW_LINKS) && Files.isRegularFile(checksumsFile, NOFOLLOW_LINKS)) {
            "Missing bundle manifest or checksums"
        }
        val manifest = Json.parseToJsonElement(Files.readString(manifestFile)) as? JsonObject
            ?: throw IllegalArgumentException("Invalid bundle manifest")
        require(manifest["schema_version"] == JsonPrimitive(1)) { "Unsupported bundle manifest" }
        val input = manifest["input_fingerprint"]?.jsonPrimitive?.takeIf { it.isString }?.content
        require(input != null && (input.isEmpty() || digest.matches(input))) { "Invalid bundle fingerprint" }
        if (fingerprint != null) require(digest.matches(fingerprint) && fingerprint == input) { "Bundle input fingerprint mismatch" }
        val expectedCount = (manifest["artifact_count"] as? JsonPrimitive)?.intOrNull
        require(expectedCount != null && expectedCount > 1) { "Invalid bundle artifact count" }
        val list = Files.readAllBytes(checksumsFile)
        require(manifest["checksums_sha256"] == JsonPrimitive(sha(list))) { "Bundle checksum list mismatch" }
        val lines = String(list).split('\n').filter(String::isNotBlank)
        require(lines.size == expectedCount) { "Bundle artifact count mismatch" }
        val root = bundle.toRealPath()
        val paths = mutableSetOf<Path>()
        for (line in lines) {
            val match = Regex("^([a-f0-9]{64})  ([A-Za-z0-9._/+:-]+)$").matchEntire(line)
                ?: throw IllegalArgumentException("Invalid artifact checksum entry")
            val name = match.groupValues[2]
            require(".." !in name && !name.startsWith('/')) { "Unsafe artifact path" }
            val file = root.resolve(name).normalize()
            require(
                file.startsWith(root) &&
                paths.add(file) &&
                Files.isRegularFile(file, NOFOLLOW_LINKS) &&
                sha(Files.readAllBytes(file)) == match.groupValues[1],
            ) { "Bundle artifact checksum mismatch: $name" }
        }
        require(paths == artifacts(root).toSet()) { "Bundle artifact set changed" }
        return true
    }
}
