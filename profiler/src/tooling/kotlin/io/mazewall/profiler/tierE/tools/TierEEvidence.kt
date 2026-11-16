package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Current file identities only; a matching manifest never establishes runtime qualification. */
internal object TierEEvidence {
    const val REPORT_LIMIT: Int = 64 * 1024
    private const val MANIFEST_LIMIT = 8 * 1024 * 1024
    private const val MAX_MEMBERS = 4096
    private val liveFields = listOf("canonical", "device", "inode", "mode", "uid", "gid", "mtime_ns", "ctime_ns")
    private val digestPattern = Regex("[a-f0-9]{64}")

    enum class Domain(
        val wireName: String,
    ) {
        LIVE("live"),
        SNAPSHOT("snapshot"),
        PREFIX("prefix"),
    }

    enum class Outcome(
        val exitCode: Int,
    ) {
        VERIFIED(0),
        MISMATCH(1),
        INVALID_MANIFEST(2),
    }

    data class Verification(
        val outcome: Outcome,
        val report: JsonObject,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    fun run(args: Array<String>): Int =
        try {
        require(args.isNotEmpty()) { "capture DOMAIN ABSOLUTE_ROOT NEW_MANIFEST MEMBER... | verify ABSOLUTE_ROOT MANIFEST NEW_REPORT" }
        when (args[0]) {
            "capture" -> {
                require(args.size >= 5) { "capture DOMAIN ABSOLUTE_ROOT NEW_MANIFEST MEMBER..." }
                val domain = domain(args[1])
                val manifest = capture(Path.of(args[2]), args.drop(4), domain)
                writeNew(Path.of(args[3]), manifest, MANIFEST_LIMIT)
                println("CAPTURED ${args.size - 4} ${domain.wireName} members")
                0
            }
            "verify" -> {
                require(args.size == 4) { "verify ABSOLUTE_ROOT MANIFEST NEW_REPORT" }
                val file = Path.of(args[2])
                require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "manifest must be a regular file" }
                val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(MANIFEST_LIMIT + 1) }
                require(bytes.size <= MANIFEST_LIMIT) { "manifest exceeds 8 MiB" }
                val result = verify(parseManifest(bytes.decodeToString(throwOnInvalidSequence = true)), Path.of(args[1]))
                writeNew(Path.of(args[3]), JsonObject(result.report + ("manifest_sha256" to JsonPrimitive(sha256(bytes)))), REPORT_LIMIT)
                println("${result.outcome} checked=${result.report.getValue("checked")} failures=${result.report.getValue("failure_count")}")
                result.outcome.exitCode
            }
            else -> throw IllegalArgumentException("unknown evidence command")
        }
    } catch (failure: Exception) {
        System.err.println("evidence check refused: ${failure.message?.take(400)}")
        Outcome.INVALID_MANIFEST.exitCode
    }

    fun parseManifest(text: String): JsonObject {
        TierEProfileJson.requireUniqueKeys(text)
        return Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("manifest must be an object")
    }

    fun capture(
        root: Path,
        paths: List<String>,
        domain: Domain,
    ): JsonObject {
        val canonical = root(root)
        require(paths.size in 1..MAX_MEMBERS && paths.distinct().size == paths.size) { "capture requires 1..4096 unique members" }
        val fields = listOf("path", "bytes", "sha256") + identityFields(domain)
        return buildJsonObject {
            put("schema_version", 1)
            put("domain", domain.wireName)
            put("entries", JsonArray(paths.map { name -> JsonObject(observe(canonical, name).filterKeys { it in fields }) }))
        }
    }

    fun verify(
        manifest: JsonElement,
        root: Path,
    ): Verification =
        try {
        val (domain, entries) = validate(manifest)
        val canonical = root(root)
        val fields = listOf("bytes", "sha256") + identityFields(domain)
        val failures = mutableListOf<JsonObject>()
        var failed = 0
        for (entry in entries) {
            val name = text(entry, "path")
            val reason = try {
                val actual = observe(canonical, name, if (domain == Domain.PREFIX) number(entry, "bytes") else null)
                val different = fields.filter { actual[it] != entry[it] }
                if (different.isEmpty()) null else "different ${different.joinToString()}"
            } catch (failure: Exception) {
                failure.toString().take(512)
            }
            if (reason != null) {
                failed++
                if (failures.size < 20) {
                    failures += buildJsonObject {
                    put("path", name)
                    put("reason", reason)
                }
                }
            }
        }
        val outcome = if (failed == 0) Outcome.VERIFIED else Outcome.MISMATCH
        Verification(
            outcome,
            JsonObject(
                base(outcome) +
            buildJsonObject {
            put("domain", domain.wireName)
            put("root", canonical.toString())
            put("checked", entries.size)
            put("failure_count", failed)
            put("failures", JsonArray(failures))
            put("truncated", failed > failures.size)
        },
            ),
        )
    } catch (failure: Exception) {
        val outcome = Outcome.INVALID_MANIFEST
        Verification(
            outcome,
            JsonObject(
                base(outcome) +
            buildJsonObject {
            put("checked", 0)
            put("failure_count", 1)
            put("failures", JsonArray(listOf(buildJsonObject { put("reason", failure.toString().take(512)) })))
            put("truncated", false)
        },
            ),
        )
    }

    fun writeNew(
        path: Path,
        value: JsonObject,
        limit: Int,
    ) {
        val bytes = (value.toString() + "\n").toByteArray()
        require(bytes.size <= limit) { "JSON output exceeds $limit bytes; nothing written" }
        Files.newByteChannel(path, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "report write made no progress" }
        }
    }

    private fun base(outcome: Outcome): JsonObject =
        buildJsonObject {
        put("schema_version", 1)
        put("execution", "FILE_IDENTITY_CHECK")
        put("qualification", false)
        put("outcome", outcome.name)
    }

    private fun validate(value: JsonElement): Pair<Domain, List<JsonObject>> {
        val manifest = value as? JsonObject ?: throw IllegalArgumentException("manifest must be an object")
        require(manifest["schema_version"] == JsonPrimitive(1)) { "unsupported or missing schema_version" }
        val domain = domain(text(manifest, "domain"))
        val rows = manifest["entries"] as? JsonArray ?: throw IllegalArgumentException("entries must be an array")
        require(rows.size in 1..MAX_MEMBERS) { "entries must contain 1..4096 members" }
        val seen = mutableSetOf<String>()
        val entries = rows.map { row ->
            val entry = row as? JsonObject ?: throw IllegalArgumentException("entry must be an object")
            val name = text(entry, "path")
            member(name)
            require(seen.add(name)) { "duplicate member" }
            require(number(entry, "bytes") >= 0) { "bytes must be nonnegative" }
            require(digestPattern.matches(text(entry, "sha256"))) { "sha256 must be a lowercase SHA-256 digest" }
            if (domain == Domain.LIVE) {
                val canonical = text(entry, "canonical")
                require(canonical.startsWith('/') && canonical.length <= 4096) { "live canonical path is required" }
                for (key in liveFields.drop(1)) number(entry, key)
            }
            entry
        }
        return domain to entries
    }

    private fun domain(value: String): Domain = requireNotNull(Domain.entries.singleOrNull { it.wireName == value }) { "unsupported identity domain" }

    private fun identityFields(domain: Domain): List<String> =
        when (domain) {
        Domain.LIVE -> liveFields
        Domain.SNAPSHOT, Domain.PREFIX -> emptyList()
    }

    private fun text(
        row: JsonObject,
        key: String,
    ): String = requireNotNull((row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content) { "$key must be a string" }

    private fun number(
        row: JsonObject,
        key: String,
    ): Long = requireNotNull((row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull) { "$key must be an integer" }

    private fun root(path: Path): Path {
        require(path.isAbsolute) { "an explicit absolute root is required" }
        return path.toRealPath()
    }

    private fun member(name: String): Path {
        require(name.isNotEmpty() && name.length <= 1024 && '\u0000' !in name) { "invalid member path" }
        val path = Path.of(name)
        require(!path.isAbsolute && name != "." && path.normalize().toString() == name && path.none { it.toString() == ".." }) { "member must be a normalized relative file path" }
        return path
    }

    private fun observe(
        root: Path,
        name: String,
        prefix: Long? = null,
    ): JsonObject {
        var file = root
        for (part in member(name)) {
            file = file.resolve(part)
            require(!Files.isSymbolicLink(file)) { "symlink member: $name" }
        }
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "missing regular member: $name" }
        val before = attributes(file)
        val size = number(before, "bytes")
        val length = prefix ?: size
        require(length <= size) { "file is shorter than captured prefix" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(file, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() == size) { "file changed before reading" }
            val buffer = ByteBuffer.allocate(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                buffer.clear().limit(minOf(remaining, buffer.capacity().toLong()).toInt())
                val count = channel.read(buffer)
                require(count > 0) { "file shortened while reading" }
                digest.update(buffer.array(), 0, count)
                remaining -= count
            }
            require(channel.size() == size && before == attributes(file)) { "file changed while reading" }
        }
        return JsonObject(
            before +
            buildJsonObject {
            put("path", name)
            put("bytes", length)
            put("sha256", HexFormat.of().formatHex(digest.digest()))
        },
        )
    }

    private fun attributes(file: Path): JsonObject = metadata(file, Files.readAttributes(file, "unix:dev,ino,mode,uid,gid,size,lastModifiedTime,ctime", NOFOLLOW_LINKS))

    fun metadata(
        file: Path,
        attributes: Map<String, Any>,
    ): JsonObject {
        return buildJsonObject {
            put("canonical", file.toString())
            for ((field, attribute) in mapOf("device" to "dev", "inode" to "ino", "bytes" to "size")) {
                put(field, (attributes.getValue(attribute) as Number).toLong())
            }
            // The JDK exposes unsigned Unix ownership IDs in signed int slots.
            for (field in listOf("uid", "gid")) put(field, Integer.toUnsignedLong((attributes.getValue(field) as Number).toInt()))
            put("mode", (attributes.getValue("mode") as Number).toInt() and 0xfff)
            put("mtime_ns", (attributes.getValue("lastModifiedTime") as FileTime).to(TimeUnit.NANOSECONDS))
            put("ctime_ns", (attributes.getValue("ctime") as FileTime).to(TimeUnit.NANOSECONDS))
        }
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
