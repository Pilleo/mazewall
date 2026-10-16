package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Tag("boundary-evidence")
class IntegrationBoundaryEvidenceTest {
    @Test
    fun `supplied observations and independent corruptions satisfy schema one`() {
        val raw = Path.of(required("boundaryRaw"))
        val custody = Path.of(required("boundaryCustody"))
        val (rows, bundle) = BoundaryEvidence.read(raw, custody)
        val summary = BoundaryEvidence.validate(rows, bundle)
        BoundaryEvidenceCorruptions.verify(rows, bundle)
        println(BoundaryEvidence.report(summary))
    }

    private fun required(name: String): String = System.getProperty(name)?.takeIf(String::isNotBlank) ?: error("Missing required -P$name")
}

class BoundaryEvidenceHarnessTest {
    @TempDir
    private lateinit var temporary: Path

    @Test
    fun `bounded ordinary results remain separate from pressure and descriptive throughput`() {
        val summary = BoundaryEvidence.validate(fixture(), BUNDLE)
        assertEquals(BoundaryMode.entries.toSet(), summary.keys)
        for ((mode, result) in summary) {
            assertEquals(BoundarySummary(2, 30, 30.0, 30, 30_000_000.0, 3, if (mode == BoundaryMode.DIRECT) 0 else 1), result)
        }
        BoundaryEvidenceCorruptions.verify(fixture(), BUNDLE)
    }

    @Test
    fun `bounded UTF8 input retains nonmeasurement diagnostics and requires companion bundle hash`() {
        val raw = temporary.resolve("raw.log")
        val custody = temporary.resolve("custody.json")
        Files.writeString(raw, "native diagnostic\n" + fixture().joinToString("\n") { it.joinToString("\t") })
        Files.writeString(custody, """{"workerBundleSha256":"$BUNDLE"}""")
        val (rows, bundle) = BoundaryEvidence.read(raw, custody)
        assertEquals(fixture(), rows)
        assertEquals(BUNDLE, bundle)
        Files.writeString(custody, "{}")
        assertEquals("custody:bundle-hash", assertThrows<IllegalArgumentException> { BoundaryEvidence.read(raw, custody) }.message)
        Files.writeString(custody, """{"workerBundleSha256":"$BUNDLE"}""")
        Files.write(raw, byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows<IllegalArgumentException> { BoundaryEvidence.read(raw, custody) }
        Files.write(raw, ByteArray(10_000_001))
        assertEquals("raw:bounded-size", assertThrows<IllegalArgumentException> { BoundaryEvidence.read(raw, custody) }.message)
        Files.writeString(raw, "diagnostic")
        Files.write(custody, ByteArray(10_000_001))
        assertEquals("custody:bounded-size", assertThrows<IllegalArgumentException> { BoundaryEvidence.read(raw, custody) }.message)
    }

    @Test
    fun `malformed and overflowing measurements fail closed with attributable reasons`() {
        data class Corruption(
            val tag: String,
            val field: Int,
            val value: String,
            val reason: String,
            val mode: String? = null,
        )
        val cases = listOf(
            Corruption("environment", 1, "", "environment:identity"),
            Corruption("environment", 5, "Options(samples=1, size=1, pressure=3, seconds=5)", "environment:bounds"),
            Corruption("environment", 5, "Options(samples=999999999999999999999, size=1, pressure=3, seconds=5)", "environment:bounds"),
            Corruption("environment", 5, "unbounded", "environment:options"),
            Corruption("checking-smoke", 1, "FAIL", "outcome:checking-smoke"),
            Corruption("sample", 3, "999999999999999999999", "configured:ids"),
            Corruption("sample", 3, "1", "configured:ids"),
            Corruption("sample", 2, "pressure", "configured:phase"),
            Corruption("sample", 5, "bad", "digest:input"),
            Corruption("sample", 12, "11", "clock:delta"),
            Corruption("sample", 13, "11", "clock:delta"),
            Corruption("throughput", 2, "2", "measurement:throughput"),
            Corruption("throughput", 3, "0", "measurement:throughput"),
            Corruption("rejected", 6, "fallback", "outcome:rejected"),
            Corruption("resources", 2, "3", "resources:host-pid", "final"),
            Corruption("resources", 4, "0", "measurement:resources"),
            Corruption("resources", 5, "-1", "measurement:resources"),
            Corruption("resources", 6, "0", "measurement:resources"),
            Corruption("physical-child-end", 1, "0", "outcome:physical-child-end"),
            Corruption("physical-thread-end", 2, "0", "outcome:physical-thread-end"),
            Corruption("portal-timing", 3, "-1", "measurement:portal-timing"),
            Corruption("portal-timing", 4, Long.MAX_VALUE.toString(), "measurement:portal-timing"),
        )
        for (case in cases) {
            val rows = fixture().map { it.toMutableList() }
            rows.first { it[0] == case.tag && (case.mode == null || it[1] == case.mode) }[case.field] = case.value
            assertEquals(case.reason, assertThrows<IllegalArgumentException> { BoundaryEvidence.validate(rows, BUNDLE) }.message, case.toString())
        }
        assertEquals("custody:bundle-hash", assertThrows<IllegalArgumentException> { BoundaryEvidence.validate(fixture(), "not-a-hash") }.message)
    }

    private fun fixture(): List<List<String>> =
        buildList {
        fun record(vararg fields: Any) {
            add(fields.map(Any::toString))
        }
        record("schema", 1, "monotonic-ns", "checking-only")
        record("environment", "25", "vendor", "Linux", "amd64", "Options(samples=2, size=1, pressure=3, seconds=5)")
        record("unavailable", "isolated-native-install,IPC,guest-body,dispatch-overhead,per-call-child-memory")
        record("selfcheck", "PASS", "unicode,bounds,overload,timeout,physical-end")
        record("checking-smoke", "PASS", "all-accepted-results-verified,physical-ends-accounted")
        record("worker-artifact", "bounded-text-worker", BUNDLE)
        record("prepare", "JDK", 1, "thread-prestart")
        record("prepare", "MANAGED", 1, "open+required-process-install")
        record("prepare", "MANAGED", 1, "domain-prepare+required-install+readiness")
        record("prepare", "PROCESS_PORTAL", 1, "metadata+bundle-catalog")
        record("prepare", "PROCESS_PORTAL", 1, "runtime-open")
        record("prepare", "PROCESS_PORTAL", 1, "binding+materialize+process-startup+required-install+readiness")
        record("physical-child-end", 2, "false")
        record("physical-thread-end", "JDK", 3, "false")
        for (label in listOf("worker-ready", "worker-after-work", "final") + BoundaryMode.entries.flatMap { listOf("$it-before", "$it-after") }) {
            record("resources", label, if (label.startsWith("worker-")) 2 else 1, "123 kB", 1, 0, 1)
        }
        for (mode in BoundaryMode.entries) {
            for (id in 0..5) {
                val phase = if (id == 0) {
                    "first-measured"
                } else if (id <= 2) {
                    "warm"
                } else {
                    "pressure"
                }
                val input = ('a' + id).toString()
                val denied = mode != BoundaryMode.DIRECT && id == 5
                if (denied) {
                    record("rejected", mode, phase, id, 1, hash(input), if (mode == BoundaryMode.PROCESS_PORTAL) "not-started-handle" else "synchronous-admission")
                } else {
                    val start = 100L + id * 100
                    record(
                        "sample",
                        mode,
                        phase,
                        id,
                        1,
                        hash(input),
                        hash(('A' + id).toString()),
                        start,
                        start + 10,
                        start + 20,
                        start + 30,
                        start + 40,
                        10,
                        10,
                        if (mode == BoundaryMode.PROCESS_PORTAL) "host-mapping+transport+worker" else "local-body",
                    )
                    if (mode == BoundaryMode.PROCESS_PORTAL && id <= 2) record("portal-timing", phase, id, 1, 2, "broker-queue,transport+worker")
                }
            }
            val accepted = if (mode == BoundaryMode.DIRECT) 6 else 5
            record("throughput", mode, 3, 100, "includes-result-verification+raw-emission")
            record("accounting", mode, 6, accepted, 6 - accepted, accepted)
            record("drain", mode, if (mode == BoundaryMode.DIRECT) 0 else 1, mode.drain)
            mode.postDrain?.let { record("post-drain", mode, it) }
        }
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUNDLE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
