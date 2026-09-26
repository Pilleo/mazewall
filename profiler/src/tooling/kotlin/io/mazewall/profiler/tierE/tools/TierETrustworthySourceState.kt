package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** All local dirt is reported, while only benchmark inputs invalidate a runtime decision. */
public object TierETrustworthySourceState {
    private val inputs = listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "gradle",
        "buildSrc",
        "enforcer",
        "platform",
        "profiler",
        "profiler-native",
        "scripts/build_tier_e_benchmark_bundle.sh",
        "scripts/run_tier_e_trustworthy_benchmark.sh",
        "scripts/run_tier_e_sustained_capacity.sh",
        "scripts/tier_e_benchmark_source_state.sh",
    )

    public fun read(repository: Path): JsonObject {
        fun count(vararg paths: String): Int {
            val process = ProcessBuilder(
                listOf("git", "-C", repository.toString(), "status", "--porcelain", "--untracked-files=normal") +
                (if (paths.isEmpty()) emptyList() else listOf("--") + paths.toList()),
            ).redirectErrorStream(true)
                .start()
            var lines = 0
            process.inputStream.bufferedReader().use { reader ->
                while (reader.readLine() != null) {
                    lines++
                    require(lines < 100_000) { "Unbounded Tier E source state" }
                }
            }
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "Git benchmark source state unavailable" }
            return lines
        }
        return buildJsonObject {
            put("schema_version", 1)
            put("all_dirty_count", count())
            put("benchmark_input_dirty_count", count(*inputs.toTypedArray()))
        }
    }
}
