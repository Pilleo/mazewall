package io.mazewall.build

import java.util.concurrent.TimeUnit

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
                    runs-on: ubuntu-26.04
                    permissions:
                        contents: read
                    steps:
                        - name: Checkout Code
                          uses: actions/checkout@v6
                        - name: Compute Container Gradle Cache Key
                          id: container_cache_key
                          run: |
                              hash=$(./scripts/ci_cache_key.sh)
                              echo "hash=${'$'}hash" >> "${'$'}GITHUB_OUTPUT"
                        - name: Set up JDK 25
                          uses: actions/setup-java@v5
                          with:
                              java-version: "25"
                              distribution: "temurin"
                        - name: Setup Gradle
                          uses: gradle/actions/setup-gradle@v6
                          with:
                              cache-read-only: false
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
                core-quality:
                    name: Core Quality and Kernel Validation
                    runs-on: ubuntu-26.04
                    permissions:
                        contents: read
                        pull-requests: write
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
                        - name: Set up JDK 25
                          uses: actions/setup-java@v5
                          with:
                              java-version: "25"
                              distribution: "temurin"
                        - name: Check file changes
                          id: filter
                          uses: dorny/paths-filter@v3
                          with:
                              filters: |
                                  code:
                                      - '**/*.kt'
                                      - '.github/workflows/ci.yml'
                        - name: Setup Gradle
                          uses: gradle/actions/setup-gradle@v6
                          with:
                              cache-read-only: false
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
                mutation-tests:
                    name: Mutation Validation
                    runs-on: ubuntu-26.04
                    permissions:
                        contents: read
                        pull-requests: read
                    steps:
                        - name: Checkout Code
                          uses: actions/checkout@v6
                        - name: Set up JDK 25
                          uses: actions/setup-java@v5
                          with:
                              java-version: "25"
                              distribution: "temurin"
                        - name: Check file changes
                          id: filter
                          uses: dorny/paths-filter@v3
                          with:
                              filters: |
                                  code:
                                      - '**/*.kt'
                                      - '.github/workflows/ci.yml'
                        - name: Setup Gradle
                          uses: gradle/actions/setup-gradle@v6
                          with:
                              cache-read-only: false
                        - name: Run PITest (Host Unit Tests Only)
                          if: steps.filter.outputs.code == 'true'
                          run: >-
                              python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/mutation.json --
                              ./gradlew :enforcer:pitest :platform:pitest :profiler:pitest
                              --no-daemon --max-workers=1 --no-parallel
                              -Pmazewall.pitest.threads=2 -Pmazewall.test.maxParallelForks=1
                        - name: Upload Mutation Diagnostics
                          if: always()
                          uses: actions/upload-artifact@v4
                          with:
                              name: mutation-diagnostics
                              path: |
                                  build/reports/ci-resources/mutation.json
                                  enforcer/build/reports/pitest/
                                  platform/build/reports/pitest/
                                  profiler/build/reports/pitest/
                              if-no-files-found: ignore
                build-test-publish:
                    name: Build, Test, Security Scan & Publish
                    needs: [core-quality, mutation-tests]
                    if: always()
                    runs-on: ubuntu-26.04
                    permissions:
                        contents: read
                    outputs:
                        container_cache_hash: ${'$'}{{ needs.core-quality.outputs.container_cache_hash }}
                    steps:
                        - name: Require Core and Mutation Success
                          run: test "${'$'}{{ needs.core-quality.result }}" = success && test "${'$'}{{ needs.mutation-tests.result }}" = success
                publish:
                    needs: [optional-integrations, build-test-publish]
                    if: github.event_name == 'push' && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master')
                    permissions:
                        contents: read
                        packages: write
                    steps:
                        - name: Restore Container Gradle Cache
                          uses: actions/cache/restore@v4
                          with:
                              key: container-gradle-${'$'}{{ runner.os }}-${'$'}{{ needs.build-test-publish.outputs.container_cache_hash }}-${'$'}{{ github.sha }}
                        - name: Publish to GitHub Packages
                          run: ./scripts/run_containerized_tests.sh publishAllPublicationsToGitHubPackagesRepository
        """.trimIndent()
        val failures = mutableListOf<String>()
        fun expectViolations(label: String, workflow: String, vararg expected: String) {
            val actual = ciCachePolicyViolations(workflow).map { it.name }
            if (actual != expected.toList()) failures += "$label: expected ${expected.toList()}, got $actual"
        }
        expectViolations("independent bounded graph", valid)
        expectViolations("PR cache writes", valid.replace("cache-read-only: false", "cache-read-only: true"), "PR_CACHE_WRITES")
        expectViolations("Dependency-Check data", valid.replace("dependency-check-data", "caches"), "DEPENDENCY_CHECK_DATA")
        expectViolations("host Dependency-Check", valid.replace("--no-configuration-cache", "--configuration-cache"), "HOST_DEPENDENCY_CHECK")
        expectViolations("container Dependency-Check", valid + "\nrun: ./scripts/run_containerized_tests.sh dependencyCheckAnalyze", "CONTAINER_DEPENDENCY_CHECK")
        expectViolations("mutable NVD cache", valid + "\nkey: nvd-database-20261014", "IMMUTABLE_NVD_CACHE")
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
            if (ciCachePolicyViolations(mutation).isEmpty()) failures += "Mutable/missing container cache identity mutation $index must fail"
        }
        val graphMutations = listOf(
            valid.replace("    core-quality:\n", "    core-quality:\n        needs: [optional-integrations]\n"),
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
            if (ciCachePolicyViolations(mutation).isEmpty()) failures += "Unsafe/serialized CI graph mutation $index must fail"
        }
        val mainStart = valid.indexOf("    core-quality:\n")
        val mainWithoutOptionalCache = valid.substring(0, mainStart) +
            valid.substring(mainStart).replace("optional-container-gradle-", "unused-container-gradle-")
        expectViolations("optional PR cache reuse", mainWithoutOptionalCache, "CONTAINER_CACHE_REUSE")

        val mutationStart = valid.indexOf("    mutation-tests:\n")
        val joinStart = valid.indexOf("    build-test-publish:\n")
        val mutationJob = valid.substring(mutationStart, joinStart)
        expectViolations(
            "missing mutation runner", valid.removeRange(mutationStart, joinStart),
            "SERIAL_VALIDATION", "VALIDATION_RUNNER", "CODE_CHANGE_FILTER", "MUTATION_EXECUTION", "MUTATION_DIAGNOSTICS",
        )
        for ((approved, abbreviated) in listOf(
            "Core Quality and Kernel Validation" to "Core Quality",
            "Mutation Validation" to "Mutation Tests",
        )) {
            expectViolations("approved lane name $approved", valid.replace("name: $approved", "name: $abbreviated"), "SERIAL_VALIDATION")
        }
        for (lane in listOf("optional-integrations", "core-quality", "mutation-tests")) {
            for (header in listOf("if: false", "needs: [core-quality]", "continue-on-error: true")) {
                expectViolations("$lane $header", valid.replace("    $lane:\n", "    $lane:\n        $header\n"), "SERIAL_VALIDATION")
            }
            val laneStart = valid.indexOf("    $lane:\n")
            val wrongRunner = valid.substring(0, laneStart) + valid.substring(laneStart).replaceFirst("ubuntu-26.04", "ubuntu-24.04")
            expectViolations("$lane kernel-capable runner", wrongRunner, "VALIDATION_RUNNER")
            val wrongJdk = valid.substring(0, laneStart) + valid.substring(laneStart).replaceFirst("java-version: \"25\"", "java-version: \"22\"")
            expectViolations("$lane JDK", wrongJdk, "VALIDATION_RUNNER")
            val readOnlyCache = valid.substring(0, laneStart) + valid.substring(laneStart).replaceFirst("cache-read-only: false", "cache-read-only: true")
            expectViolations("$lane PR cache writes", readOnlyCache, "PR_CACHE_WRITES")
        }
        for (bound in listOf("--max-workers=1", "--no-parallel", "-Pmazewall.pitest.threads=2", "-Pmazewall.test.maxParallelForks=1")) {
            expectViolations("missing $bound", valid.replace(bound, ""), "MUTATION_EXECUTION")
        }
        val executionMutations = listOf(
            mutationJob.replace("./gradlew :enforcer:pitest", "./scripts/run_containerized_tests.sh :enforcer:pitest"),
            mutationJob.replace("python3 scripts/ci_resource_probe.py", "python3 scripts/other.py"),
            mutationJob.replace("--output build/reports/ci-resources/mutation.json", "--output other.json"),
            mutationJob.replace(":platform:pitest ", ""),
            mutationJob.replace("maxParallelForks=1", "maxParallelForks=1 &"),
            mutationJob.replace("maxParallelForks=1", "maxParallelForks=1 || true"),
            mutationJob.replace("if: steps.filter.outputs.code == 'true'", "if: false"),
            mutationJob.replace("if: steps.filter.outputs.code == 'true'", "continue-on-error: true\n              if: steps.filter.outputs.code == 'true'"),
            mutationJob.replace("- name: Run PITest", "- name: Setup Podman\n              run: podman info\n            - name: Run PITest"),
        )
        executionMutations.forEachIndexed { index, job ->
            expectViolations("unsafe mutation command $index", valid.replace(mutationJob, job), "MUTATION_EXECUTION")
        }
        val coreCacheSave = "            - name: Save Container Gradle Cache (Master Only)"
        expectViolations(
            "background PIT in core", valid.replace(
                coreCacheSave, "            - name: Background PIT\n              run: ./gradlew :enforcer:pitest &\n$coreCacheSave",
            ),
            "MUTATION_EXECUTION",
        )
        expectViolations(
            "colocated mutation", valid.removeRange(mutationStart, joinStart).replace(
                coreCacheSave, "            - name: Background PIT\n              run: ./gradlew :enforcer:pitest :platform:pitest :profiler:pitest &\n$coreCacheSave",
            ),
            "SERIAL_VALIDATION", "VALIDATION_RUNNER", "CODE_CHANGE_FILTER", "MUTATION_EXECUTION", "MUTATION_DIAGNOSTICS",
        )
        for (lane in listOf("core-quality", "mutation-tests")) {
            val start = valid.indexOf("    $lane:\n")
            val removedFilter = valid.substring(0, start) + valid.substring(start).replaceFirst("- '.github/workflows/ci.yml'", "- 'README.md'")
            expectViolations("$lane workflow change must run gates", removedFilter, "CODE_CHANGE_FILTER")
            val malformedFilter = valid.substring(0, start) + valid.substring(start).replaceFirst("- '.github/workflows/ci.yml'", " - '.github/workflows/ci.yml'")
            expectViolations("$lane malformed embedded filter", malformedFilter, "CODE_CHANGE_FILTER")
            val withoutPrRead = valid.substring(0, start) + valid.substring(start).replaceFirst(Regex("pull-requests: (read|write)"), "pull-requests: none")
            expectViolations("$lane PR filter API permission", withoutPrRead, "CODE_CHANGE_FILTER")
            for (unsafeHeader in listOf("if: false", "continue-on-error: true")) {
                val skippedFilter = valid.substring(0, start) + valid.substring(start).replaceFirst(
                    "- name: Check file changes", "- name: Check file changes\n              $unsafeHeader",
                )
                expectViolations("$lane filter $unsafeHeader", skippedFilter, "CODE_CHANGE_FILTER")
            }
        }
        for (receipt in listOf("if: always()", "build/reports/ci-resources/mutation.json", "enforcer/build/reports/pitest/", "platform/build/reports/pitest/", "profiler/build/reports/pitest/")) {
            // Last occurrence selects the upload, not the probe's output argument.
            val index = mutationJob.lastIndexOf(receipt)
            val missingReceipt = mutationJob.removeRange(index, index + receipt.length)
            expectViolations("missing always-uploaded $receipt", valid.replace(mutationJob, missingReceipt), "MUTATION_DIAGNOSTICS")
        }
        val joinJob = valid.substring(joinStart, valid.indexOf("    publish:\n"))
        for ((from, to) in listOf(
            "name: Build, Test, Security Scan & Publish" to "name: Renamed required check",
            "needs: [core-quality, mutation-tests]" to "needs: [core-quality]",
            "if: always()" to "if: success()",
            "if: always()" to "if: always() && !cancelled()",
            "&& test" to "|| test",
            "needs.mutation-tests.result" to "needs.core-quality.result",
            "    build-test-publish:\n" to "    build-test-publish:\n        continue-on-error: true\n",
            "- name: Require Core and Mutation Success" to "- name: Require Core and Mutation Success\n              continue-on-error: true",
        )) {
            expectViolations("fail-closed join $from", valid.replace(joinJob, joinJob.replace(from, to)), "REQUIRED_CHECK_JOIN")
        }
        expectViolations(
            "cache identity forwarding", valid.replace("needs.core-quality.outputs.container_cache_hash", "needs.core-quality.outputs.wrong"), "PUBLISH_GRAPH",
        )
        for (lane in listOf("optional-integrations", "core-quality", "mutation-tests", "build-test-publish")) {
            expectViolations(
                "$lane cannot publish", valid.replace("    $lane:\n", "    $lane:\n        permissions:\n            packages: write\n"), "PACKAGE_WRITE_SCOPE",
            )
        }
        expectViolations("no workflow-wide package write", "permissions:\n    packages: write\n$valid", "PACKAGE_WRITE_SCOPE")
        expectViolations("no workflow-wide write-all", "permissions: write-all\n$valid", "PACKAGE_WRITE_SCOPE")
        expectViolations("no flow-style package write", "permissions: {packages: write}\n$valid", "PACKAGE_WRITE_SCOPE")

        val joinCommand = joinJob.substringAfter("              run: ").trim()
        for (core in listOf("success", "failure", "cancelled", "skipped", null)) {
            for (mutation in listOf("success", "failure", "cancelled", "skipped", null)) {
                val command = joinCommand.replace("${'$'}{{ needs.core-quality.result }}", core.orEmpty())
                    .replace("${'$'}{{ needs.mutation-tests.result }}", mutation.orEmpty())
                check(shellSucceeds(command) == (core == "success" && mutation == "success")) {
                    "Actual required-check shell join must fail closed for core=$core mutation=$mutation"
                }
            }
        }
        val publishCondition = valid.substringAfter("    publish:\n").lineSequence().map(String::trim)
            .first { it.startsWith("if: ") }.removePrefix("if: ")
        for (event in listOf("push", "pull_request")) {
            for (ref in listOf("refs/heads/main", "refs/heads/master", "refs/heads/feature")) {
                val expression = publishCondition.replace("github.event_name", "'$event'").replace("github.ref", "'$ref'")
                check(shellSucceeds("[[ $expression ]]") == (event == "push" && ref != "refs/heads/feature")) {
                    "Actual publication condition must reject PRs and non-default pushes: $event $ref"
                }
            }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
        println("CI policy: cache regressions, three independent bounded lanes, 25 shell join outcomes, default-push publication and PR-only cancellation passed.")
    }

    private fun shellSucceeds(command: String): Boolean {
        val process = ProcessBuilder("bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command).inheritIO().start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("CI condition shell timed out: $command")
        }
        return process.exitValue() == 0
    }
}
