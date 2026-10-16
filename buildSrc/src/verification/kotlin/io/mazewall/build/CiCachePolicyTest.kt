package io.mazewall.build

/** Public JVM entrypoint is consumed by the mandatory Java verification harness. */
object CiCachePolicyTest {
    @JvmStatic
    fun run() {
        val valid = """
            cache-read-only: false
            dependency-check-data
            run: ./gradlew dependencyCheckAnalyze --info --no-configuration-cache --no-daemon
        """.trimIndent()
        check(ciCachePolicyViolations(valid).isEmpty())
        check(ciCachePolicyViolations(valid.replace("cache-read-only: false", "cache-read-only: true")) == listOf(CiCacheViolation.PR_CACHE_WRITES))
        check(ciCachePolicyViolations(valid.replace("dependency-check-data", "caches")) == listOf(CiCacheViolation.DEPENDENCY_CHECK_DATA))
        check(ciCachePolicyViolations(valid.replace("--no-configuration-cache", "--configuration-cache")) == listOf(CiCacheViolation.HOST_DEPENDENCY_CHECK))
        check(ciCachePolicyViolations(valid + "\nrun: ./scripts/run_containerized_tests.sh dependencyCheckAnalyze") == listOf(CiCacheViolation.CONTAINER_DEPENDENCY_CHECK))
        check(ciCachePolicyViolations(valid + "\nkey: nvd-database-20261014") == listOf(CiCacheViolation.IMMUTABLE_NVD_CACHE))
    }
}
