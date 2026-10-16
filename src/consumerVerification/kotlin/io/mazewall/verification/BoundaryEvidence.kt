package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal enum class BoundaryMode(
    val drain: String,
    val postDrain: String?,
) {
    DIRECT("synchronous-no-owner", null),
    JDK("terminated+joined", "REJECTED"),
    MANAGED("Complete", "REJECTED"),
    PROCESS_PORTAL("close-returned", "NOT_STARTED"),
}

internal data class BoundarySummary(
    val warmCount: Int,
    val warmMin: Long,
    val warmMedian: Double,
    val warmMax: Long,
    val harnessCompletionsPerSecond: Double,
    val pressureOffered: Int,
    val pressureRejected: Int,
)

internal object BoundaryEvidence {
    val shapes = linkedMapOf(
        "schema" to 4,
        "environment" to 6,
        "unavailable" to 2,
        "prepare" to 4,
        "worker-artifact" to 3,
        "resources" to 7,
        "sample" to 15,
        "portal-timing" to 6,
        "rejected" to 7,
        "throughput" to 5,
        "accounting" to 6,
        "drain" to 4,
        "post-drain" to 3,
        "physical-child-end" to 3,
        "physical-thread-end" to 4,
        "selfcheck" to 3,
        "checking-smoke" to 3,
    )
    private val preparation = setOf(
        "JDK" to "thread-prestart",
        "MANAGED" to "open+required-process-install",
        "MANAGED" to "domain-prepare+required-install+readiness",
        "PROCESS_PORTAL" to "metadata+bundle-catalog",
        "PROCESS_PORTAL" to "runtime-open",
        "PROCESS_PORTAL" to "binding+materialize+process-startup+required-install+readiness",
    )
    private val options = Regex("Options\\(samples=([0-9]+), size=([0-9]+), pressure=([0-9]+), seconds=([0-9]+)\\)")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private const val UNAVAILABLE = "isolated-native-install,IPC,guest-body,dispatch-overhead,per-call-child-memory"

    fun read(
        raw: Path,
        custody: Path,
    ): Pair<List<List<String>>, String> {
        val text = readBounded(raw, "raw")
        val manifest = Json.parseToJsonElement(readBounded(custody, "custody")).jsonObject
        val hash = manifest["workerBundleSha256"] as? JsonPrimitive
        require(hash != null && hash.isString && hashPattern.matches(hash.content)) { "custody:bundle-hash" }
        return text
            .lineSequence()
            .filter { '\t' in it }
            .map { it.split('\t') }
            .toList() to hash.content
    }

    private fun readBounded(
        path: Path,
        label: String,
    ): String {
        val bytes = Files.newInputStream(path).use { it.readNBytes(10_000_001) }
        require(bytes.size <= 10_000_000) { "$label:bounded-size" }
        return try {
            UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("$label:utf8", failure)
        }
    }

    fun validate(
        rows: List<List<String>>,
        bundle: String,
    ): Map<BoundaryMode, BoundarySummary> {
        require(hashPattern.matches(bundle)) { "custody:bundle-hash" }
        require(rows.all { it.isNotEmpty() && shapes[it[0]] == it.size }) { "schema:tag-or-shape" }
        val grouped = rows.groupBy { it[0] }
        for (tag in shapes.keys) require(!grouped[tag].isNullOrEmpty()) { "family:$tag" }

        fun records(tag: String): List<List<String>> = grouped.getValue(tag)
        require(records("schema") == listOf(listOf("schema", "1", "monotonic-ns", "checking-only"))) { "schema:identity" }
        require(records("unavailable") == listOf(listOf("unavailable", UNAVAILABLE))) { "attribution:unavailable" }
        require(records("selfcheck") == listOf(listOf("selfcheck", "PASS", "unicode,bounds,overload,timeout,physical-end"))) { "outcome:selfcheck" }
        require(records("checking-smoke") == listOf(listOf("checking-smoke", "PASS", "all-accepted-results-verified,physical-ends-accounted"))) { "outcome:checking-smoke" }
        require(records("worker-artifact") == listOf(listOf("worker-artifact", "bounded-text-worker", bundle))) { "custody:worker-artifact" }
        val environment = records("environment")
        require(environment.size == 1 && environment.single().subList(1, 5).all { it.isNotEmpty() }) { "environment:identity" }
        val match = options.matchEntire(environment.single()[5]) ?: throw IllegalArgumentException("environment:options")
        val counts = match.groupValues.drop(1).map { number(it, "environment:bounds") }
        require(counts[0] in 2..1000 && counts[1] in 1..65536 && counts[2] in 3..64 && counts[3] in 5..600) { "environment:bounds" }
        val samplesCount = counts[0].toInt()
        val size = counts[1].toInt()
        val pressure = counts[2].toInt()
        val prepare = records("prepare")
        require(prepare.size == preparation.size && prepare.map { it[1] to it[3] }.toSet() == preparation) { "attribution:prepare" }
        require(prepare.all { number(it[2], "measurement:prepare") > 0 }) { "measurement:prepare" }
        val modes = BoundaryMode.entries.map { it.name }.toSet()
        for (tag in listOf("sample", "rejected", "throughput", "accounting", "drain", "post-drain")) {
            require(records(tag).all { it[1] in modes }) { "mode:$tag" }
        }
        for (tag in listOf("throughput", "accounting", "drain")) {
            require(records(tag).size == 4 && records(tag).map { it[1] }.toSet() == modes) { "family:$tag" }
        }
        val postDrain = BoundaryMode.entries.mapNotNull { mode -> mode.postDrain?.let { mode.name to it } }.toMap()
        require(records("post-drain").size == 3 && records("post-drain").associate { it[1] to it[2] } == postDrain) { "outcome:post-drain" }
        require(records("drain").associate { it[1] to it[3] } == BoundaryMode.entries.associate { it.name to it.drain }) { "outcome:drain" }
        require(records("drain").all { if (it[1] == "DIRECT") number(it[2], "measurement:drain") == 0L else number(it[2], "measurement:drain") > 0 }) { "measurement:drain" }
        val ends = records("physical-child-end")
        require(ends.size == 1 && number(ends[0][1], "outcome:physical-child-end") > 0 && ends[0][2] == "false") { "outcome:physical-child-end" }
        val threadEnds = records("physical-thread-end")
        require(threadEnds.size == 1 && threadEnds[0][1] == "JDK" && number(threadEnds[0][2], "outcome:physical-thread-end") > 0 && threadEnds[0][3] == "false") { "outcome:physical-thread-end" }
        val resources = records("resources")
        val resourceLabels = setOf("worker-ready", "worker-after-work", "final") + modes.flatMap { listOf("$it-before", "$it-after") }
        require(resources.size == resourceLabels.size && resources.map { it[1] }.toSet() == resourceLabels) { "family:resources" }
        val hostPids = resources.filterNot { it[1].startsWith("worker-") }.map { it[2] }.toSet()
        require(hostPids.size == 1 && ends[0][1] !in hostPids) { "resources:host-pid" }
        for (record in resources) {
            require(
                number(record[2], "measurement:resources") > 0 &&
                Regex("[0-9]+ kB").matches(record[3]) &&
                number(record[4], "measurement:resources") > 0 &&
                number(record[5], "measurement:resources") >= 0 &&
                number(record[6], "measurement:resources") > 0,
            ) { "measurement:resources" }
            require(!record[1].startsWith("worker-") || record[2] == ends[0][1]) { "resources:child-pid" }
        }
        val summary = linkedMapOf<BoundaryMode, BoundarySummary>()
        val ordinary = mutableMapOf<BoundaryMode, Map<Pair<String, String>, List<String>>>()
        val total = samplesCount + 1 + pressure
        for (mode in BoundaryMode.entries) {
            val selected = records("sample").filter { it[1] == mode.name }
            val rejected = records("rejected").filter { it[1] == mode.name }
            val accounting = records("accounting").single { it[1] == mode.name }.drop(2).map { number(it, "configured:accounting") }
            val denied = if (mode == BoundaryMode.DIRECT) 0 else pressure - 2
            val accepted = total - denied
            require(accounting == listOf(total.toLong(), accepted.toLong(), denied.toLong(), accepted.toLong())) { "configured:accounting" }
            require(selected.size == accepted && rejected.size == denied) { "accounting:records" }
            val ids = (selected + rejected).map { number(it[3], "configured:ids") }
            require(ids.size == total && ids.toSet() == (0L until total.toLong()).toSet()) { "configured:ids" }
            for (record in selected + rejected) {
                val requestId = number(record[3], "configured:ids").toInt()
                require(number(record[4], "configured:size") == size.toLong()) { "configured:size" }
                val phase = if (requestId == 0) {
                    "first-measured"
                } else if (requestId <= samplesCount) {
                    "warm"
                } else {
                    "pressure"
                }
                require(record[2] == phase) { "configured:phase" }
                val input = ByteArray(size) { ('a'.code + (requestId + it) % 26).toByte() }
                require(record[5] == sha256(input)) { "digest:input" }
                if (record[0] == "rejected") {
                    require(mode != BoundaryMode.DIRECT && requestId >= samplesCount + 3) { "configured:rejection-id" }
                    require(record[6] == if (mode == BoundaryMode.PROCESS_PORTAL) "not-started-handle" else "synchronous-admission") { "outcome:rejected" }
                    continue
                }
                require(mode == BoundaryMode.DIRECT || requestId < samplesCount + 3) { "configured:accepted-id" }
                require(record[6] == sha256(ByteArray(size) { (input[it] - 32).toByte() })) { "digest:output" }
                val clocks = record.subList(7, 12).map { number(it, "clock:order") }
                require(clocks.first() > 0 && clocks.zipWithNext().all { (before, after) -> before <= after }) { "clock:order" }
                require(number(record[12], "clock:delta") == clocks[1] - clocks[0] && number(record[13], "clock:delta") == clocks[2] - clocks[1]) { "clock:delta" }
                require(record[14] == if (mode == BoundaryMode.PROCESS_PORTAL) "host-mapping+transport+worker" else "local-body") { "attribution:sample" }
            }
            val normal = selected.filter { it[2] != "pressure" }
            ordinary[mode] = normal.associateBy { it[2] to it[3] }
            require(normal.size == samplesCount + 1) { "configured:ordinary" }
            val warm = normal.filter { it[2] == "warm" }.map { number(it[10], "clock:order") - number(it[7], "clock:order") }.sorted()
            val throughput = records("throughput").single { it[1] == mode.name }
            require(throughput[4] == "includes-result-verification+raw-emission") { "attribution:throughput" }
            require(number(throughput[2], "measurement:throughput") == normal.size.toLong() && number(throughput[3], "measurement:throughput") > 0) { "measurement:throughput" }
            val median = warm[warm.size / 2].toDouble() / 2 + warm[(warm.size - 1) / 2].toDouble() / 2
            summary[mode] = BoundarySummary(warm.size, warm.first(), median, warm.last(), normal.size * 1e9 / number(throughput[3], "measurement:throughput"), pressure, denied)
        }
        val direct = ordinary.getValue(BoundaryMode.DIRECT).mapValues { (_, row) -> row.subList(4, 7) }
        require(ordinary.values.all { records -> records.mapValues { (_, row) -> row.subList(4, 7) } == direct }) { "equivalence:ordinary" }
        val timings = records("portal-timing")
        val portal = ordinary.getValue(BoundaryMode.PROCESS_PORTAL)
        require(timings.size == samplesCount + 1 && timings.map { it[1] to it[2] }.toSet() == portal.keys) { "portal:timing-ids" }
        for (record in timings) {
            require(record[5] == "broker-queue,transport+worker") { "attribution:portal-timing" }
            val interval = number(portal.getValue(record[1] to record[2])[13], "clock:delta")
            val queue = number(record[3], "measurement:portal-timing")
            val execution = number(record[4], "measurement:portal-timing")
            require(queue >= 0 && execution >= 0 && queue <= interval && execution <= interval - queue) { "measurement:portal-timing" }
        }
        return summary
    }

    private fun number(
        value: String,
        reason: String,
    ): Long = value.toLongOrNull() ?: throw IllegalArgumentException(reason)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun report(summary: Map<BoundaryMode, BoundarySummary>): String =
        buildJsonObject {
        put("scope", "checking smoke; descriptive, order/JIT/verification effects included; no performance conclusion; artifact authenticity requires companion custody verification")
        put(
            "modes",
            buildJsonObject {
            for ((mode, result) in summary) {
                put(
                    mode.name,
                    buildJsonObject {
                put(
                    "warm_result_observed_ns",
                    buildJsonObject {
                    put("n", result.warmCount)
                    put("min", result.warmMin)
                    put("median", result.warmMedian)
                    put("max", result.warmMax)
                },
                )
                put("harness_ordinary_completions_per_second_including_checks_and_emission", result.harnessCompletionsPerSecond)
                put("pressure_offered", result.pressureOffered)
                put("pressure_rejected", result.pressureRejected)
            },
                )
            }
        },
        )
    }.toString()
}
