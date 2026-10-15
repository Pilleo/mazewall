package io.mazewall.verification

internal object BoundaryEvidenceCorruptions {
    fun verify(
        rows: List<List<String>>,
        bundle: String,
    ) {
        val cases = mutableSetOf<String>()

        fun reject(
            name: String,
            mutated: List<List<String>>,
            expected: String,
        ) {
            check(cases.add(name)) { "selfcheck:duplicate-case:$name" }
            check(mutated != rows) { "selfcheck:noop-dataset:$name" }
            val failure = try {
                BoundaryEvidence.validate(mutated, bundle)
                null
            } catch (failure: IllegalArgumentException) {
                failure
            }
            check(failure != null) { "selfcheck:accepted-corruption:$name" }
            check(failure.message == expected) { "selfcheck:$name:unexpected-reason:$failure" }
            println("RAW_EXPECTED_REJECT $name $expected")
        }

        fun mutate(
            name: String,
            expected: String,
            change: (MutableList<MutableList<String>>) -> Unit,
        ) {
            val mutated = rows.map { it.toMutableList() }.toMutableList()
            change(mutated)
            reject(name, mutated, expected)
        }

        fun change(
            name: String,
            tag: String,
            index: Int,
            expected: String,
            mode: String? = null,
            value: (String) -> String,
        ) {
            mutate(name, expected) { mutated ->
                val record = mutated.first { it[0] == tag && (mode == null || it[1] == mode) }
                val replacement = value(record[index])
                check(replacement != record[index]) { "selfcheck:noop-field:$name" }
                record[index] = replacement
            }
        }
        for (tag in BoundaryEvidence.shapes.keys) reject("missing-family-$tag", rows.filterNot { it[0] == tag }, "family:$tag")
        for ((index, record) in rows.withIndex()) {
            if (record[0] in setOf("prepare", "resources")) {
                reject("missing-record-${record[0]}-${record[1]}-$index", rows.filterIndexed { i, _ -> i != index }, if (record[0] == "prepare") "attribution:prepare" else "family:resources")
            }
        }
        for (mode in BoundaryMode.entries) {
            change("sample-label-$mode", "sample", 14, "attribution:sample", mode.name) { "guest-body" }
            change("throughput-label-$mode", "throughput", 4, "attribution:throughput", mode.name) { "isolated-throughput" }
            change("drain-outcome-$mode", "drain", 3, "outcome:drain", mode.name) { "Incomplete" }
            change("drain-duration-$mode", "drain", 2, "measurement:drain", mode.name) { "-1" }
            if (mode.postDrain != null) change("post-drain-$mode", "post-drain", 2, "outcome:post-drain", mode.name) { "ACCEPTED" }
        }
        change("portal-label", "portal-timing", 5, "attribution:portal-timing") { "isolated-IPC" }
        change("portal-wrong-id", "portal-timing", 2, "portal:timing-ids") { (-it.toLong() - 1).toString() }
        change("portal-wrong-phase", "portal-timing", 1, "portal:timing-ids") { "pressure" }
        mutate("portal-duplicated-id", "portal:timing-ids") { mutated ->
            val timings = mutated.filter { it[0] == "portal-timing" }
            timings[1][1] = timings[0][1]
            timings[1][2] = timings[0][2]
        }
        val excessive = rows.filter { it[0] == "sample" && it[1] == "PROCESS_PORTAL" }.maxOf { it[13].toLong() } + 1
        change("portal-duration", "portal-timing", 4, "measurement:portal-timing") { excessive.toString() }
        change("bundle-identity", "worker-artifact", 2, "custody:worker-artifact") { (if (it[0] == '0') "1" else "0") + it.drop(1) }
        change("schema-version", "schema", 1, "schema:identity") { "2" }
        change("unavailable-label", "unavailable", 1, "attribution:unavailable") { "nothing" }
        change("prepare-label", "prepare", 3, "attribution:prepare") { "isolated-native-install" }
        change("prepare-duration", "prepare", 2, "measurement:prepare") { "-1" }
        change("resource-unit", "resources", 3, "measurement:resources") { "123 bytes" }
        change("resource-label", "resources", 1, "family:resources") { "isolated-allocation" }
        change("resource-pid", "resources", 2, "resources:child-pid", "worker-ready") { (it.toLong() + 1).toString() }
        change("selfcheck-outcome", "selfcheck", 1, "outcome:selfcheck") { "FAIL" }
        change("unknown-mode", "sample", 1, "mode:sample") { "UNKNOWN" }
        change("unknown-tag", "sample", 0, "schema:tag-or-shape") { "invented" }
        change("configured-size", "sample", 4, "configured:size") { if (it.toLong() == 1L) "2" else "1" }
        mutate("truncated-record", "schema:tag-or-shape") { mutated -> mutated.first { it[0] == "sample" }.removeLast() }
        change("environment-counts", "environment", 5, "configured:accounting") { value ->
            Regex("samples=([0-9]+)").replaceFirst(value, "samples=" + if (Regex("samples=([0-9]+)").find(value)!!.groupValues[1].toLong() == 2L) "3" else "2")
        }
        change("counter", "accounting", 3, "configured:accounting") { (it.toLong() + 1).toString() }
        change("output-digest", "sample", 6, "digest:output") { "bad-digest" }
        change("clock", "sample", 8, "clock:order") { "0" }
        change("live-child", "physical-child-end", 2, "outcome:physical-child-end") { "true" }
        change("live-thread", "physical-thread-end", 3, "outcome:physical-thread-end") { "true" }

        fun recalculate(mutated: MutableList<MutableList<String>>) {
            for (record in mutated.filter { it[0] == "accounting" }) {
                val accepted = mutated.count { it[0] == "sample" && it[1] == record[1] }
                val denied = mutated.count { it[0] == "rejected" && it[1] == record[1] }
                record[2] = (accepted + denied).toString()
                record[3] = accepted.toString()
                record[4] = denied.toString()
                record[5] = accepted.toString()
            }
        }
        mutate("consistent-counters-missing-configured-pressure", "family:rejected") { mutated ->
            mutated.removeAll { it[0] in setOf("sample", "rejected") && it[2] == "pressure" }
            recalculate(mutated)
        }
        val removedId = rows.first { it[0] == "sample" && it[1] == "MANAGED" && it[2] == "pressure" }[3]
        mutate("consistent-counters-truncated-pressure-all-families-present", "configured:accounting") { mutated ->
            mutated.removeAll { it[0] in setOf("sample", "rejected") && it[3] == removedId }
            recalculate(mutated)
        }
        reject("missing-managed-samples-other-families-present", rows.filterNot { it[0] == "sample" && it[1] == "MANAGED" }, "accounting:records")
        println("RAW_SELFCHECK PASS ${cases.size} independent-corruptions")
    }
}
