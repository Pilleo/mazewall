package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Runtime-source fingerprint compatible with the historical split matrix. */
public object TierESourceFingerprint {
    private val inputs = listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "buildSrc",
        "config/detekt",
        "enforcer/src",
        "platform/src",
        "profiler/src",
        "profiler-native",
        "scripts",
        "infra/dev/Containerfile",
    )
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    public fun read(
        repository: Path,
        previous: JsonObject? = null,
    ): JsonObject {
        val root = repository.toRealPath()
        val revision = String(git(root, "rev-parse", "HEAD"), StandardCharsets.UTF_8).trim()
        val listed = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *inputs.toTypedArray())
        val paths = String(listed, StandardCharsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.toSortedSet()
        val aggregate = MessageDigest.getInstance("SHA-256")
        val fileRecords = linkedMapOf<String, Pair<String, String>>()
        for (name in paths) {
            val file = root.resolve(name)
            val (kind, bytes) = when {
                !Files.exists(file, NOFOLLOW_LINKS) -> "missing" to "MISSING".toByteArray(StandardCharsets.US_ASCII)
                Files.isSymbolicLink(file) -> "symlink" to Files.readSymbolicLink(file).toString().toByteArray(StandardCharsets.UTF_8)
                Files.isRegularFile(file, NOFOLLOW_LINKS) -> {
                    require(Files.size(file) <= MAX_FILE_BYTES) { "source input exceeds $MAX_FILE_BYTES bytes: $name" }
                    val permissions = Files.getPosixFilePermissions(file, NOFOLLOW_LINKS)
                    val executable = permissions.any { it in setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE) }
                    (if (executable) "executable" else "regular") to Files.readAllBytes(file)
                }
                else -> error("unsupported source input type: $name")
            }
            val digest = bytes.sha256()
            fileRecords[name] = kind to digest
            aggregate.update(name.toByteArray(StandardCharsets.UTF_8))
            aggregate.update(0)
            aggregate.update(kind.toByteArray(StandardCharsets.US_ASCII))
            aggregate.update(0)
            aggregate.update(digest.toByteArray(StandardCharsets.US_ASCII))
            aggregate.update('\n'.code.toByte())
        }
        val dirty = String(git(root, "status", "--porcelain", "--untracked-files=normal", "--", *inputs.toTypedArray()), StandardCharsets.UTF_8)
            .lineSequence()
            .count { it.isNotEmpty() }
        val digest = aggregate.digest().toHex()
        return buildJsonObject {
            put("schema_version", 1)
            put("revision", revision)
            put("source_digest", digest)
            put("source_file_count", fileRecords.size)
            put("input_dirty_count", dirty)
            put(
                "files",
                buildJsonObject {
                for ((name, value) in fileRecords) {
                    put(
                        name,
                        buildJsonObject {
                        put("kind", value.first)
                        put("sha256", value.second)
                    },
                    )
                }
            },
            )
            if (previous != null) {
                put(
                    "unchanged",
                    previous["source_digest"]?.jsonPrimitive?.content == digest &&
                previous["revision"]?.jsonPrimitive?.content == revision,
                )
            }
        }
    }

    private fun git(
        root: Path,
        vararg args: String,
    ): ByteArray {
        val output = Files.createTempFile("tier-e-git-", ".out")
        try {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args.toList())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                error("git command timed out: ${args.firstOrNull()}")
            }
            check(process.exitValue() == 0) { "git ${args.firstOrNull()}: ${Files.readString(output).take(256)}" }
            return Files.readAllBytes(output)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
