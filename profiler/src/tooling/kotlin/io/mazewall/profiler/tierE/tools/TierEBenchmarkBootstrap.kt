package io.mazewall.profiler.tierE.tools

import java.nio.file.Path

internal object TierEBenchmarkBootstrap {
    fun command(
        repository: Path,
        bundle: Path,
        usdt: Boolean,
        nativePackage: Path?,
    ): List<String> =
        listOf(
            "bash",
            "-euc",
            """
            repository=${'$'}1
            cache=${'$'}{MAZEWALL_TIER_E_BUILD_CACHE_ROOT:-"${'$'}repository/build/tier-e-benchmark-cache"}
            mkdir -p "${'$'}cache/gradle-home" "${'$'}cache/gradle-bootstrap-project-cache"
            GRADLE_USER_HOME="${'$'}cache/gradle-home" "${'$'}repository/gradlew" --no-daemon \
                --project-cache-dir "${'$'}cache/gradle-bootstrap-project-cache" :profiler:installDist
            exec "${'$'}repository/profiler/build/install/tier-e-daemon/bin/tier-e-tools" \
                bundle-build "${'$'}1" "${'$'}2" "${'$'}3" false "${'$'}{@:4}"
            """.trimIndent(),
            "tier-e-bundle-bootstrap",
            repository.toString(),
            bundle.toString(),
            usdt.toString(),
        ) +
        listOfNotNull(nativePackage?.toString())
}
