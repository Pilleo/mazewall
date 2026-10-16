package io.mazewall.profiler.tierE.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Independently validates every successful invocation-token receipt before volume retirement. */
public object TierETrustworthyInvocationWitnessVerifier {
    public fun verify(
        output: Path,
        variant: String,
        fork: Int,
        report: String,
        declared: Set<Int>,
        targetPid: Int,
        workloadCpu: String,
        oracleSha256: String,
        rawProgramSha256: String,
        mainProgramSha256: String,
    ) {
        fun field(key: String): String =
            Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)").findAll(report).map { it.groupValues[1] }.singleOrNull()
                ?: error("missing or duplicate invocation-witness receipt field $key")
        check(field("invocation_witness_contract_version") == "1" && field("invocation_witness_complete") == "true") {
            "invocation witness did not produce a complete measured fork"
        }
        val affinityLog = output.resolve("child-logs/$variant-fork-$fork.log")
        check(Files.isRegularFile(affinityLog, NOFOLLOW_LINKS)) { "missing target startup-affinity receipt" }
        val affinity = Files.readAllLines(affinityLog).singleOrNull { it.startsWith("tier-e-pre-gate-affinity: ") }
        val targetAffinity = affinity?.let {
            Regex("tier-e-pre-gate-affinity: workload_cpu=([0-9]+) threads=([1-9][0-9]*) launched_pinned=true").matchEntire(it)
        }
        check(
            targetAffinity != null &&
            targetAffinity.groupValues[1] == workloadCpu &&
            field("raw_sys_enter_audit_cpu_counts") == "$workloadCpu:${declared.size}" &&
            field("raw_sys_enter_audit_cpu_total").toIntOrNull() == declared.size &&
            field("raw_sys_enter_audit_observed_operations").toIntOrNull() == declared.size,
        ) { "audited target JVM affinity or raw event CPU counts differ from declared workload CPU" }
        val filename = "invocation-fd-$variant-fork-$fork.mzif"
        val native = output.resolve(filename)
        check(Files.isRegularFile(native, NOFOLLOW_LINKS) && Files.size(native) in 48..(32L + 65_536L * 16)) {
            "missing or oversized original native invocation-FD witness"
        }
        val nativeSha = TierETrustworthyPreGateExport.sha256(native)
        check(field("invocation_witness_native_sha256") == nativeSha) { "native invocation witness changed since source receipt" }
        val data = ByteBuffer.wrap(Files.readAllBytes(native)).order(ByteOrder.LITTLE_ENDIAN)
        check(
            data.int == 0x46495a4d &&
            data.short.toInt() == 1 &&
            data.short.toInt() == 16 &&
            data.int == 0x4d5a &&
            data.int == 0,
        ) { "invalid versioned native invocation-FD header" }
        val records = data.long
        val dropped = data.long
        check(records in 1..65_536 && dropped == 0L && data.capacity().toLong() == 32 + records * 16) {
            "native invocation-FD rows were dropped or truncated"
        }
        val ids = HashSet<Long>(records.toInt())
        val nativeDeclared = HashMap<Int, Long>(declared.size)
        repeat(records.toInt()) {
            val id = data.long
            val fd = data.int
            val reserved = data.int
            check(id != 0L && (id ushr 32).toInt() == 0x4d5a && ids.add(id) && fd >= 0 && reserved == 0) {
                "native invocation witness contains stale or repeated IDs"
            }
            if (fd in declared) check(nativeDeclared.put(fd, id) == null) { "duplicate target FD in native witness" }
        }
        check(nativeDeclared.keys == declared) { "native invocation witness does not cover all target-authored FDs" }

        val sidecar = output.resolve("invocation-witness-$variant-fork-$fork.tsv")
        check(
            Files.isRegularFile(sidecar, NOFOLLOW_LINKS) &&
            Files.size(sidecar) <= 16L * 1024L * 1024L &&
            field("invocation_witness_evidence_file") == "/artifacts/${sidecar.fileName}" &&
            field("invocation_witness_evidence_sha256") == TierETrustworthyPreGateExport.sha256(sidecar) &&
            field("invocation_witness_evidence_rows").toIntOrNull() == declared.size,
        ) { "unbound or torn invocation witness sidecar" }
        val lines = Files.readAllLines(sidecar)
        val header = lines.firstOrNull()?.split('\t').orEmpty()
        val failureCounts = field("invocation_witness_failure_counts")
        val noToken = Regex("9:0,10:0,11:([0-9]+),12:([0-9]+),13:0,14:0").matchEntire(failureCounts)
        val rawSurplus = noToken?.groupValues?.get(1)?.toLongOrNull()
        val mainSurplus = noToken?.groupValues?.get(2)?.toLongOrNull()
        val expected = declared.size.toLong()
        check(
            rawSurplus != null &&
            mainSurplus != null &&
            field("invocation_witness_map_entries").toLongOrNull() == expected &&
            field("invocation_witness_map_raw_total").toLongOrNull() == expected &&
            field("invocation_witness_map_main_total").toLongOrNull() == expected &&
            field("invocation_witness_ambient_raw").toLongOrNull() == rawSurplus &&
            field("invocation_witness_ambient_main").toLongOrNull() == mainSurplus &&
            field("raw_sys_enter_audit_pre_decode_target_openat").toLongOrNull() == Math.addExact(expected, rawSurplus) &&
            field("exact_admission_audit_pre_decode_target_openat").toLongOrNull() == Math.addExact(expected, mainSurplus),
        ) { "zero-token surplus does not conserve all declared raw and main target observations" }
        val failures = "failure_counts=$failureCounts"
        check(
            lines.size == declared.size + 2 &&
            "schema_version=1" in header &&
            "target_tgid=$targetPid" in header &&
            "rows=${declared.size}" in header &&
            "declared_sha256=$oracleSha256" in header &&
            "native_sha256=$nativeSha" in header &&
            "raw_program_sha256=$rawProgramSha256" in header &&
            "main_program_sha256=$mainProgramSha256" in header &&
            failures in header,
        ) { "invocation witness does not bind the original manifest and both loaded programs" }
        check(lines[1] == "fd\tinvocation_id\traw_token\tmain_token\traw_before_session\tmain_before_session\tearliest_stage") {
            "invalid invocation witness stage columns"
        }
        val sortedFds = declared.sorted()
        lines.drop(2).forEachIndexed { index, line ->
            val parts = line.split('\t')
            val fd = sortedFds[index]
            check(
                parts.size == 7 &&
                parts[0].toIntOrNull() == fd &&
                parts[1].toLongOrNull() == nativeDeclared[fd] &&
                parts.subList(2, 6).all { it == "1" } &&
                parts[6] == "FD_CHECKPOINT_PRESENT",
            ) { "invocation witness lost or substituted declared FD $fd" }
        }
    }
}
