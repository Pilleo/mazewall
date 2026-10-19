package io.mazewall.build

/** Public JVM entrypoint is consumed by the mandatory Java verification harness. */
object CiCachePolicyTest {
    @JvmStatic
    fun run() {
        val valid = """
            cache-read-only: false
            dependency-check-data
            run: ./gradlew dependencyCheckAnalyze --info --no-configuration-cache --no-daemon
            concurrency:
                group: ${'$'}{{ github.workflow }}-${'$'}{{ github.event_name }}-${'$'}{{ github.event.pull_request.number || github.run_id }}
                cancel-in-progress: ${'$'}{{ github.event_name == 'pull_request' }}
            jobs:
                optional-integrations:
                    name: Koog and Spring Integration Acceptance
                    steps:
                        - name: Checkout Code
                          uses: actions/checkout@v6
                        - name: Compute Container Gradle Cache Key
                          id: container_cache_key
                          run: |
                              hash=$(./scripts/ci_cache_key.sh)
                              echo "hash=${'$'}hash" >> "${'$'}GITHUB_OUTPUT"
                        - name: Restore Optional Container Gradle Cache
                          uses: actions/cache/restore@v4
                          with:
                              key: optional-container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}
                              restore-keys: |
                                  optional-container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-
                                  optional-container-gradle-${'$'}{{ runner.os }}-
                                  container-gradle-${'$'}{{ runner.os }}-
                        - name: Save Optional Container Gradle Cache
                          if: always() && steps.optional_ownership.outcome == 'success' && steps.container_cache_key.outcome == 'success'
                          uses: actions/cache/save@v4
                          with:
                              key: optional-container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}
                build-test-publish:
                    name: Build, Test, Security Scan & Publish
                    outputs:
                        container_cache_hash: ${'$'}{{ steps.container_cache_key.outputs.hash }}
                    steps:
                        - name: Checkout Code
                          uses: actions/checkout@v6
                        - name: Compute Container Gradle Cache Key
                          id: container_cache_key
                          run: |
                              hash=$(./scripts/ci_cache_key.sh)
                              echo "hash=${'$'}hash" >> "${'$'}GITHUB_OUTPUT"
                        - name: Restore Container Gradle Cache
                          uses: actions/cache/restore@v4
                          with:
                              key: container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}
                              restore-keys: |
                                  container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-
                                  optional-container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-
                                  optional-container-gradle-${'$'}{{ runner.os }}-
                                  container-gradle-${'$'}{{ runner.os }}-
                        - name: Save Container Gradle Cache (Master Only)
                          uses: actions/cache/save@v4
                          if: always() && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master') && steps.container_cache_key.outcome == 'success'
                          with:
                              key: container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}
                publish:
                    needs: [optional-integrations, build-test-publish]
                    if: github.event_name == 'push' && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master')
                    steps:
                        - name: Restore Container Gradle Cache
                          uses: actions/cache/restore@v4
                          with:
                              key: container-gradle-${'$'}{{ runner.os }}-${'$'}{{ needs.build-test-publish.outputs.container_cache_hash }}-${'$'}{{ github.sha }}
                        - name: Publish to GitHub Packages
                          run: ./scripts/run_containerized_tests.sh publishAllPublicationsToGitHubPackagesRepository
        """.trimIndent()
        check(ciCachePolicyViolations(valid).isEmpty())
        check(ciCachePolicyViolations(valid.replace("cache-read-only: false", "cache-read-only: true")) == listOf(CiCacheViolation.PR_CACHE_WRITES))
        check(ciCachePolicyViolations(valid.replace("dependency-check-data", "caches")) == listOf(CiCacheViolation.DEPENDENCY_CHECK_DATA))
        check(ciCachePolicyViolations(valid.replace("--no-configuration-cache", "--configuration-cache")) == listOf(CiCacheViolation.HOST_DEPENDENCY_CHECK))
        check(ciCachePolicyViolations(valid + "\nrun: ./scripts/run_containerized_tests.sh dependencyCheckAnalyze") == listOf(CiCacheViolation.CONTAINER_DEPENDENCY_CHECK))
        check(ciCachePolicyViolations(valid + "\nkey: nvd-database-20261014") == listOf(CiCacheViolation.IMMUTABLE_NVD_CACHE))
        val output = "${'$'}{{ steps.container_cache_key.outputs.hash }}"
        val producerId = "id: container_cache_key"
        val saveGuard = " && steps.container_cache_key.outcome == 'success'"
        val mutations = listOf(
            valid.replace(output, "${'$'}{{ hashFiles('**/*.gradle*', '**/gradle.properties', 'gradle/libs.versions.toml') }}"),
            valid.replaceFirst(output, "${'$'}{{ steps.wrong.outputs.hash }}"),
            valid.replaceFirst(output, ""),
            valid.replaceFirst("id: container_cache_key", "id: wrong"),
            valid.replaceFirst("hash=\$(./scripts/ci_cache_key.sh)", "hash=unused"),
            valid.replaceFirst("echo \"hash=${'$'}hash\" >> \"${'$'}GITHUB_OUTPUT\"", "echo unused"),
            valid.replaceFirst(" && steps.container_cache_key.outcome == 'success'", ""),
            valid.replace(" && steps.container_cache_key.outcome == 'success'", ""),
            valid.replaceRange(valid.lastIndexOf(output), valid.lastIndexOf(output) + output.length, ""),
            valid.replaceRange(valid.lastIndexOf(producerId), valid.lastIndexOf(producerId) + producerId.length, "id: wrong"),
            valid.replaceRange(valid.lastIndexOf(saveGuard), valid.lastIndexOf(saveGuard) + saveGuard.length, ""),
        )
        mutations.forEachIndexed { index, mutation ->
            check(ciCachePolicyViolations(mutation).isNotEmpty()) { "Mutable/missing container cache identity mutation $index must fail" }
        }
        val graphMutations = listOf(
            valid.replace("    build-test-publish:\n", "    build-test-publish:\n        needs: [optional-integrations]\n"),
            valid.replace("    optional-integrations:\n", "    optional-integrations:\n        needs: [build-test-publish]\n"),
            valid.replace("    optional-integrations:\n", "    optional-integrations:\n        if: false\n"),
            valid.replace("name: Koog and Spring Integration Acceptance", "name: Renamed required check"),
            valid.replace("needs: [optional-integrations, build-test-publish]", "needs: [build-test-publish]"),
            valid.replace("needs: [optional-integrations, build-test-publish]", "needs: [optional-integrations]"),
            valid.replace("if: github.event_name == 'push'", "if: always() && github.event_name == 'push'"),
            valid.replace("if: github.event_name == 'push'", "if: github.event_name == 'pull_request'"),
            valid.replace("container_cache_hash: ${'$'}{{ steps.container_cache_key.outputs.hash }}", "container_cache_hash: missing"),
            valid.replace("needs.build-test-publish.outputs.container_cache_hash", "needs.build-test-publish.outputs.wrong"),
            valid.replace("github.event.pull_request.number || github.run_id", "github.sha"),
            valid.replace("cancel-in-progress: ${'$'}{{ github.event_name == 'pull_request' }}", "cancel-in-progress: true"),
            valid + "\nrun: ./gradlew publishAllPublicationsToGitHubPackagesRepository\n",
        )
        graphMutations.forEachIndexed { index, mutation ->
            check(ciCachePolicyViolations(mutation).isNotEmpty()) { "Unsafe/serialized CI graph mutation $index must fail" }
        }
        val mainStart = valid.indexOf("    build-test-publish:\n")
        val mainWithoutOptionalCache = valid.substring(0, mainStart) +
            valid.substring(mainStart).replace("optional-container-gradle-", "unused-container-gradle-")
        check(ciCachePolicyViolations(mainWithoutOptionalCache) == listOf(CiCacheViolation.CONTAINER_CACHE_REUSE)) {
            "Main container verification must reuse compatible optional PR caches before older main fallback"
        }
        println("CI policy: cache invariants, parallel validation, gated publication and PR-only cancellation mutations passed.")
    }
}
