package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierEActiveEntrypointPolicyTest {
    @Test
    fun `active launchers may not reintroduce Node Python Docker or matrix loops`() {
        val scratch = Files.createTempFile("tier-e-entrypoint-", ".sh")
        try {
            Files.writeString(scratch, "#!/usr/bin/env bash\nnode analyze.mjs\n")
            assertTrue(TierEActiveEntrypointPolicy.check(scratch).isNotEmpty())
            Files.writeString(scratch, "#!/usr/bin/env bash\nexec bash \"\${ROOT}/scripts/tier_e_tools\" coverage-summary \"\$@\"\n")
            assertTrue(TierEActiveEntrypointPolicy.check(scratch).isEmpty())
        } finally {
            Files.deleteIfExists(scratch)
        }
    }

    @Test
    fun `every supported active Tier E entrypoint is a launcher`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_tier_e_trustworthy_benchmark.sh")) }
        val discovered = Files.list(root.resolve("scripts")).use { files ->
            files
                .map { it.fileName.toString() }
                .filter {
                    it.matches(Regex("(?:run_tier_e_|build_tier_e_|compare_tier_e_|check_tier_e_).*\\.sh")) &&
                    !it.endsWith("_legacy.sh")
                }.toList()
                .toSet()
        }
        assertTrue(
            TierEActiveEntrypointPolicy.active.toSet().containsAll(discovered),
            "unregistered Tier E script: ${discovered - TierEActiveEntrypointPolicy.active.toSet()}",
        )
        assertTrue(
            TierEActiveEntrypointPolicy.active.all { path ->
            TierEActiveEntrypointPolicy.check(root.resolve("scripts/$path")).isEmpty()
        },
        )
    }
}
