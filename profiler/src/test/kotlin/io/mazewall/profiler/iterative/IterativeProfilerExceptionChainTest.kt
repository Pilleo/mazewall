package io.mazewall.profiler.iterative

import io.mazewall.Policy
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertTrue

class IterativeProfilerExceptionChainTest {

    @Test
    fun `test iterative profiling handles wrapped exception chains`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(io.mazewall.Platform.isSupported())
        val basePolicy = Policy.PURE_COMPUTE_UNSAFE
        val targetPath = "/etc/wrapped_denied_path"

        val compiledPolicy = IterativeProfiler.profile(basePolicy) {
            val root = IOException("$targetPath (Permission denied)")
            val wrapper = RuntimeException("framework wrapper", root)
            throw wrapper
        }

        // This will fail currently because IterativeProfiler only looks at the top-level exception
        assertTrue(compiledPolicy.allowedFsReadPaths.any { it.value == targetPath },
            "Should have extracted path from wrapped exception")
    }
}
