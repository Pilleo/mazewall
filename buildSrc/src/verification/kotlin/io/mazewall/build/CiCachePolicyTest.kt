package io.mazewall.build

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Public JVM entrypoint is consumed by the mandatory Java verification harness. */
object CiCachePolicyTest {
    @JvmStatic
    fun run() {
        verifySecuritySnapshotRecovery()
        verifyCompletedSecurityLifecycle()
        verifyBuildLogicLane()
        verifyGoLane()
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
        """.trimIndent().let { legacy ->
            val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
            val actualSecurity = job(repository.resolve(".github/workflows/ci.yml").readText(), "security-check")
            val actualBuildLogic = job(repository.resolve(".github/workflows/ci.yml").readText(), "build-logic-validation")
            val actualGo = job(repository.resolve(".github/workflows/ci.yml").readText(), "go-validation")
            val fixtureFilter = step(job(legacy, "core-quality"), "Check file changes").trimEnd()
            val securityFixture = actualSecurity.replace(step(actualSecurity, "Check file changes").trimEnd(), fixtureFilter)
            legacy.replace("    optional-integrations:\n", securityFixture.trimEnd() + "\n" + actualBuildLogic.trimEnd() + "\n" + actualGo.trimEnd() + "\n    optional-integrations:\n")
                .replace("needs: [core-quality, mutation-tests]", "needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation]")
                .replace("test \"\${{ needs.mutation-tests.result }}\" = success", "test \"\${{ needs.mutation-tests.result }}\" = success && test \"\${{ needs.security-check.result }}\" = success && test \"\${{ needs.build-logic-validation.result }}\" = success && test \"\${{ needs.go-validation.result }}\" = success")
        }
        val failures = mutableListOf<String>()
        fun expectViolations(label: String, workflow: String, vararg expected: String) {
            val actual = ciCachePolicyViolations(workflow).map { it.name }
            if (actual != expected.toList()) failures += "$label: expected ${expected.toList()}, got $actual"
        }
        expectViolations("independent bounded graph", valid)
        expectViolations("PR cache writes", valid.replace("cache-read-only: false", "cache-read-only: true"), "PR_CACHE_WRITES", "BUILD_LOGIC_VALIDATION")
        expectViolations("Dependency-Check data", valid.replace("dependency-check-data", "caches"), "DEPENDENCY_CHECK_DATA", "SECURITY_LIFECYCLE")
        expectViolations("host Dependency-Check", valid.replace("--no-configuration-cache", "--configuration-cache"), "SECURITY_LIFECYCLE", "HOST_DEPENDENCY_CHECK")
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
            expectViolations("missing $bound", valid.replace(mutationJob, mutationJob.replace(bound, "")), "MUTATION_EXECUTION")
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
            "needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation]" to "needs: [core-quality]",
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
                    .replace("${'$'}{{ needs.security-check.result }}", "success")
                    .replace("${'$'}{{ needs.build-logic-validation.result }}", "success")
                    .replace("${'$'}{{ needs.go-validation.result }}", "success")
                check("\${{" !in command) { "Unresolved legacy join expression" }
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
        println("CI policy: cache/graph regressions, required security/build-logic/Go checks, join projections, default-push publication and PR-only cancellation passed.")
    }

    private fun verifyBuildLogicLane() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val lane = job(workflow, "build-logic-validation")
        check(lane.isNotEmpty()) { "Missing independent build-logic-validation lane" }
        check(ciCachePolicyViolations(workflow).isEmpty()) { "Actual workflow must satisfy build-logic policy" }
        val mutations = listOf(
            lane to "",
            "    build-logic-validation:\n" to "    build-logic-validation:\n        needs: [core-quality]\n",
            "    build-logic-validation:\n" to "    build-logic-validation:\n        if: false\n",
            "    build-logic-validation:\n" to "    build-logic-validation:\n        continue-on-error: true\n",
            "name: Build Logic Validation" to "name: Unrequired Build Logic",
            "ubuntu-26.04" to "ubuntu-24.04",
            "timeout-minutes: 90" to "timeout-minutes: 180",
            "contents: read" to "contents: write",
            "java-version: \"25\"" to "java-version: \"22\"",
            "cache-read-only: false" to "cache-read-only: true",
            "gradle/actions/setup-gradle@v6" to "gradle/actions/setup-gradle@v5",
            "set -o pipefail" to "set +o pipefail",
            "python3 scripts/ci_resource_probe.py" to "python3 scripts/other.py",
            "--output build/reports/ci-resources/build-logic.json" to "--output other.json",
            "buildLogicCheck" to "check",
            "--configuration-cache " to "",
            "--configuration-cache-problems=fail" to "--configuration-cache-problems=warn",
            "--no-daemon" to "",
            "--max-workers=1" to "--max-workers=2",
            "--no-parallel" to "--parallel",
            "- name: Run Build Logic Verification" to "- name: Run Build Logic Verification\n              if: false",
            "- name: Run Build Logic Verification" to "- name: Run Build Logic Verification\n              continue-on-error: true",
            "2>&1 | tee" to "|| true 2>&1 | tee",
            "if: always()" to "if: success()",
            "build/reports/ci-resources/build-logic.log\n" to "",
            "build/reports/ci-resources/build-logic.json\n" to "",
            "buildSrc/build/reports/" to "",
            "buildSrc/build/test-results/" to "",
            "buildSrc/build/verification/" to "",
        )
        for ((from, to) in mutations) {
            check(from in lane && from != to) { "Ineffective build-logic mutation: $from" }
            check(ciCachePolicyViolations(workflow.replace(lane, lane.replace(from, to))).isNotEmpty()) {
                "Unsafe build-logic lane accepted: $from"
            }
        }
        val join = shell(step(job(workflow, "build-test-publish"), "Require Core and Mutation Success"))
        val results = listOf("success", "failure", "cancelled", "skipped", "")
        var checked = 0
        for (core in results) for (mutation in results) for (security in results) for (buildLogic in results) {
            val command = join.replace("\${{ needs.core-quality.result }}", core)
                .replace("\${{ needs.mutation-tests.result }}", mutation)
                .replace("\${{ needs.security-check.result }}", security)
                .replace("\${{ needs.build-logic-validation.result }}", buildLogic)
                .replace("\${{ needs.go-validation.result }}", "success")
            check("\${{" !in command) { "Unresolved four-way join expression" }
            check(shellSucceeds(command) == (core == "success" && mutation == "success" && security == "success" && buildLogic == "success")) {
                "Actual 625-way join failed: $core/$mutation/$security/$buildLogic"
            }
            checked++
        }
        check(checked == 625)
        val prerequisites = listOf("core-quality", "mutation-tests", "security-check", "build-logic-validation", "go-validation")
        for (prerequisite in prerequisites) {
            val removedNeed = workflow.replace(
                "needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation]",
                "needs: [${prerequisites.filter { it != prerequisite }.joinToString(", ")}]",
            )
            check(removedNeed != workflow && ciCachePolicyViolations(removedNeed).isNotEmpty()) { "Missing join prerequisite accepted: $prerequisite" }
            val removedResult = workflow.replace(" && test \"\${{ needs.$prerequisite.result }}\" = success", "")
                .replace("test \"\${{ needs.$prerequisite.result }}\" = success && ", "")
            check(removedResult != workflow && ciCachePolicyViolations(removedResult).isNotEmpty()) { "Missing literal success accepted: $prerequisite" }
        }
        println("Actual four-result projection: 625 tuples with Go success; unsafe build-logic lanes rejected.")
    }

    private fun verifyGoLane() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val lane = job(workflow, "go-validation")
        check(lane.isNotEmpty()) { "Missing required path-gated Go validation lane" }
        for ((from, to) in listOf(
            lane to "",
            "    go-validation:\n" to "    go-validation:\n        if: false\n",
            "    go-validation:\n" to "    go-validation:\n        needs: [core-quality]\n",
            "    go-validation:\n" to "    go-validation:\n        continue-on-error: true\n",
            "profiler/hotspot-agentless/**" to "other/**",
            "experiments/hotspot-agentless/**" to "other/**",
            "steps.go_changes.outputs.go == 'true'" to "false",
            "actions/setup-go@v6" to "actions/setup-go@v5",
            "go-version-file: profiler/hotspot-agentless/go.mod" to "go-version: stable",
            "govulncheck@v1.9.0" to "govulncheck@latest",
            "GOMEMLIMIT: 4GiB" to "GOMEMLIMIT: 16GiB",
            "GOMAXPROCS: \"2\"" to "GOMAXPROCS: \"8\"",
            "bash -euo pipefail" to "bash",
            "python3 scripts/ci_resource_probe.py" to "python3 other.py",
            "if ! make -C \"\$module\" unit; then result=1; fi" to "true",
            "if ! make -C \"\$module\" vuln; then result=1; fi" to "true",
            "exit \"\$result\"" to "exit 0",
            "if: always()" to "if: success()",
        )) {
            check(from in lane && from != to) { "Ineffective Go mutation: $from" }
            check(ciCachePolicyViolations(workflow.replace(lane, lane.replace(from, to))).isNotEmpty()) { "Unsafe Go lane accepted: $from" }
        }
        val command = shell(step(lane, "Run Go Checks")).substringAfter("-- bash -euo pipefail -c '")
            .substringBeforeLast("' 2>&1")
        check(command.contains("for module in ")) { "Missing actual Go workload shell" }
        val scratch = Files.createTempDirectory("ci-go-workload-").toFile()
        try {
            val calls = scratch.resolve("calls")
            scratch.resolve("make").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$2:\$3\" >>\"\$CALLS\"\n[ \"\$FAILURE\" != all ] && [ \"\$FAILURE\" != \"\$2:\$3\" ]\n")
                check(setExecutable(true))
            }
            val expected = listOf("profiler/hotspot-agentless:unit", "profiler/hotspot-agentless:vuln", "experiments/hotspot-agentless:unit", "experiments/hotspot-agentless:vuln")
            for (failure in listOf("") + expected + "all") {
                calls.writeText("")
                val success = shellSucceeds(command, mapOf("PATH" to scratch.path + File.pathSeparator + System.getenv("PATH"), "CALLS" to calls.path, "FAILURE" to failure))
                check(success == failure.isEmpty()) { "Go failure was masked: $failure" }
                check(calls.readLines() == expected) { "A Go check was omitted after failure: $failure" }
            }
        } finally {
            scratch.deleteRecursively()
        }
        val join = shell(step(job(workflow, "build-test-publish"), "Require Core and Mutation Success"))
        for (result in listOf("success", "failure", "cancelled", "skipped", "")) {
            var actual = join
            for (name in listOf("core-quality", "mutation-tests", "security-check", "build-logic-validation")) {
                actual = actual.replace("\${{ needs.$name.result }}", "success")
            }
            actual = actual.replace("\${{ needs.go-validation.result }}", result)
            check("\${{" !in actual && shellSucceeds(actual) == (result == "success")) { "Go result must be required: $result" }
        }
        println("Actual Go workload runs both modules, preserves every failure, and is required by publication.")
    }

    private fun shellSucceeds(command: String, environment: Map<String, String> = emptyMap()): Boolean {
        val process = ProcessBuilder("bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command).apply {
            environment().putAll(environment)
        }.inheritIO().start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("CI condition shell timed out: $command")
        }
        return process.exitValue() == 0
    }

    private fun verifySecuritySnapshotRecovery() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val security = job(repository.resolve(".github/workflows/ci.yml").readText(), "security-check")
        val selection = step(security, "Select Retained Security Snapshot")
        check(selection.isNotEmpty()) { "A missing NVD cache must recover a compatible retained snapshot before cold bootstrap" }
        val selectionCondition = selection.lineSequence().map(String::trim).first { it.startsWith("if: ") }.removePrefix("if: ")
        for (code in listOf(false, true)) for (eligible in listOf(false, true)) {
            for ((matched, exact) in listOf("" to false, "dc-data-v1-Linux-X64-dc10.0.4_90-1" to false, "dc-data-v1-Linux-X64-dc10.0.4_100-1" to true)) {
                val expression = selectionCondition.replace("steps.filter.outputs.code", "'$code'")
                    .replace("steps.security_metadata.outputs.eligible", "'$eligible'")
                    .replace("steps.security_restore.outputs.cache-matched-key", "'$matched'")
                    .replace("steps.security_restore.outputs.cache-hit", "'$exact'")
                check(shellSucceeds("[[ $expression ]]") == (code && eligible && matched.isEmpty())) {
                    "Artifact recovery must distinguish a prefix cache hit from a genuine miss"
                }
            }
        }
        val scratch = Files.createTempDirectory("ci-security-recovery-").toFile()
        try {
            val preparation = shell(step(security, "Prepare Security Snapshot Tools"))
            check(preparation.isNotBlank()) { "Missing executable snapshot integrity validation" }
            val runnerTemp = scratch.resolve("runner-temp").apply { mkdir() }
            val prepare = ProcessBuilder("bash", "-euo", "pipefail", "-c", preparation).apply {
                environment()["RUNNER_TEMP"] = runnerTemp.path
            }.inheritIO().start()
            check(prepare.waitFor(5, TimeUnit.SECONDS) && prepare.exitValue() == 0)
            val controls = scratch.resolve("selection.py").apply {
                writeText("""
                    import json, os, pathlib, subprocess, sys
                    root, tool = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
                    prefix = 'dc-data-v1-Linux-X64-dc10.0.4_'
                    def run(ident, **changes):
                        return dict(id=ident, workflow_id=7, event='push', head_branch='master', head_sha='abc',
                                    repository={'id': 11}, head_repository={'id': 11}, pull_requests=[], run_attempt=2,
                                    status='completed', conclusion='failure') | changes
                    def artifact(ident, run_id, **changes):
                        return dict(id=ident, name=f'security-snapshot-{prefix}{run_id}-1', expired=False,
                                    size_in_bytes=123, digest='sha256:' + 'a' * 64,
                                    created_at='2026-10-21T10:00:00Z', expires_at='2099-01-01T00:00:00Z',
                                    workflow_run=dict(id=run_id, repository_id=11, head_repository_id=11,
                                                      head_branch='master', head_sha='abc')) | changes
                    def choose(candidates, current=None, expected_exit=0):
                        output = root / 'selection-output'
                        output.write_text('')
                        payload = root / 'candidates.json'
                        payload.write_text(json.dumps(dict(repository_id=11, default_branch='master', pr_number=554,
                                                           current=current or run(100), candidates=candidates)))
                        result = subprocess.run([sys.executable, str(tool), 'select', str(payload)], capture_output=True,
                                                text=True, env=dict(os.environ, GITHUB_OUTPUT=str(output), SNAPSHOT_PREFIX=prefix), timeout=5)
                        assert result.returncode == expected_exit, result.stdout + result.stderr
                        return dict(line.split('=', 1) for line in output.read_text().splitlines())
                    good = artifact(201, 90)
                    assert choose([]) == {}
                    assert choose([dict(artifact=good, run=run(90))]) == dict(artifact_id='201', run_id='90', key=prefix + '90-1', digest='sha256:' + 'a' * 64, size='123')
                    newer = artifact(202, 91, created_at='2026-10-21T11:00:00Z', digest='sha256:' + 'b' * 64, size_in_bytes=124)
                    newest = choose([dict(artifact=good, run=run(90)), dict(artifact=newer, run=run(91))])
                    assert newest['artifact_id'] == '202' and newest['digest'] == 'sha256:' + 'b' * 64 and newest['size'] == '124'
                    for digest in [None, '', 'md5:' + 'a' * 32, 'sha256:' + 'a' * 63, 'sha256:' + 'z' * 64]:
                        assert choose([dict(artifact=good, run=run(90)), dict(artifact=newer | dict(digest=digest), run=run(91))], expected_exit=1) == {}
                    for changes in [
                        dict(expired=True), dict(size_in_bytes=0), dict(size_in_bytes=2147483649),
                        dict(created_at='invalid'), dict(expires_at='2000-01-01T00:00:00Z'),
                        dict(name=good['name'].replace('10.0.4_', '10.0.4-rc1_')),
                        dict(name=good['name'].replace('X64', 'ARM64')), dict(name=good['name'].replace('Linux', 'Windows')),
                        dict(name=good['name'].replace('90-1', '90-0')), dict(name=good['name'] + '-incomplete')
                    ]:
                        assert choose([dict(artifact=good | changes, run=run(90))]) == {}, changes
                    for changes in [
                        dict(workflow_id=8), dict(repository={'id': 12}), dict(head_repository={'id': 12}),
                        dict(head_repository=None), dict(head_branch='feature'), dict(event='workflow_dispatch'),
                        dict(head_sha='different'), dict(run_attempt=0),
                        dict(event='pull_request', head_branch='feature', pull_requests=[{'number': 554}])
                    ]:
                        assert choose([dict(artifact=good, run=run(90, **changes))]) == {}, changes
                    pr = run(100, event='pull_request', head_branch='feature', head_repository={'id': 12}, pull_requests=[{'number': 554}])
                    own_pr = pr | dict(id=90)
                    pr_artifact = artifact(201, 90, workflow_run=dict(id=90, repository_id=11,
                                          head_repository_id=12, head_branch='feature', head_sha='abc'))
                    assert choose([dict(artifact=pr_artifact, run=own_pr)], pr)['artifact_id'] == '201'
                    assert choose([dict(artifact=pr_artifact, run=own_pr | dict(pull_requests=[{'number': 555}]))], pr) == {}
                    assert choose([dict(artifact=good, run=run(90))], pr)['artifact_id'] == '201'
                    assert choose([dict(artifact=pr_artifact, run=own_pr)]) == {}
                    print('Retained snapshot selection: recovery, newest, scope, compatibility and deleted fork passed.')
                """.trimIndent())
            }
            val process = ProcessBuilder("python3", controls.path, scratch.path, runnerTemp.resolve("security-snapshot.py").path)
                .inheritIO().start()
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Snapshot selection controls timed out" }
            check(process.exitValue() == 0) { "Retained snapshot selection controls failed" }

            val integrity = scratch.resolve("integrity.py").apply {
                writeText("""
                    import hashlib, itertools, json, os, pathlib, shutil, stat, subprocess, sys, zipfile
                    root = pathlib.Path(sys.argv[1])
                    tool = pathlib.Path(sys.argv[2])
                    source = root / 'database'
                    source.mkdir()
                    (source / 'odc.mv.db').write_bytes(b'fixture database, not a real scanner pass')
                    (source / 'cache').mkdir()
                    (source / 'cache' / '.state').write_bytes(b'preserved hidden file')
                    artifact = root / 'artifact'
                    destination = root / 'restored'
                    archive_ids = itertools.count()
                    def archive_snapshot(directory):
                        path = root / f'input-{next(archive_ids)}.zip'
                        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
                            for entry in directory.rglob('*'):
                                if entry.is_file():
                                    archive.write(entry, entry.relative_to(directory).as_posix())
                        return path
                    def invoke(mode, src, dst, key='dc-data-v1-Linux-X64-dc10.0.4_90-1', digest=None):
                        env = dict(os.environ, SNAPSHOT_KEY=key)
                        if mode == 'restore':
                            if src.is_dir():
                                src = archive_snapshot(src)
                            if digest is None:
                                with src.open('rb') as stream:
                                    digest = 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest()
                        if digest is not None:
                            env['SNAPSHOT_DIGEST'] = digest
                        result = subprocess.run([sys.executable, str(tool), mode, str(src), str(dst)], env=env,
                                                capture_output=True, text=True, timeout=5)
                        return result.returncode, result.stdout + result.stderr
                    code, log = invoke('pack', source, artifact)
                    assert code == 0, log
                    code, log = invoke('restore', artifact, destination)
                    assert code == 0, log
                    assert (destination / 'odc.mv.db').read_bytes() == (source / 'odc.mv.db').read_bytes()
                    assert (destination / 'cache' / '.state').read_bytes() == b'preserved hidden file'
                    assert not (destination / 'manifest.json').exists()
                    for digest in ['', 'md5:' + 'a' * 32, 'sha256:' + '0' * 64]:
                        assert invoke('restore', artifact, root / 'invalid-service-digest', digest=digest)[0] != 0
                        assert not (root / 'invalid-service-digest').exists()
                    original_archive = root / 'service-original.zip'
                    with zipfile.ZipFile(original_archive, 'w', zipfile.ZIP_DEFLATED) as archive:
                        for path in artifact.rglob('*'):
                            if path.is_file():
                                archive.write(path, path.relative_to(artifact).as_posix())
                    service_digest = 'sha256:' + hashlib.sha256(original_archive.read_bytes()).hexdigest()
                    original_database = (artifact / 'data' / 'odc.mv.db').read_bytes()
                    original_manifest = (artifact / 'manifest.json').read_bytes()
                    replacement = b'changed data with a self-consistent manifest'
                    (artifact / 'data' / 'odc.mv.db').write_bytes(replacement)
                    forged = json.loads(original_manifest)
                    forged['files']['odc.mv.db'] = dict(size=len(replacement), sha256=hashlib.sha256(replacement).hexdigest())
                    (artifact / 'manifest.json').write_text(json.dumps(forged))
                    assert invoke('restore', artifact, root / 'service-mismatch', digest=service_digest)[0] != 0, 'A GitHub archive digest mismatch must block even a self-consistent replacement'
                    assert not (root / 'service-mismatch').exists()
                    (artifact / 'data' / 'odc.mv.db').write_bytes(original_database)
                    (artifact / 'manifest.json').write_bytes(original_manifest)
                    for name in ['../escape', str(root / 'absolute-escape'), 'data/../../escape', 'data\\escape']:
                        archive_path = archive_snapshot(artifact)
                        with zipfile.ZipFile(archive_path, 'a') as archive:
                            archive.writestr(name, b'escape')
                        assert invoke('restore', archive_path, root / 'unsafe-archive')[0] != 0
                        assert not (root / 'unsafe-archive').exists()
                    assert not (root / 'escape').exists()
                    assert not (root / 'absolute-escape').exists()
                    for kind in [stat.S_IFLNK, stat.S_IFIFO]:
                        archive_path = root / f'nonregular-{kind}.zip'
                        with zipfile.ZipFile(archive_path, 'w') as archive:
                            for path in artifact.rglob('*'):
                                if path.is_file():
                                    name = path.relative_to(artifact).as_posix()
                                    if name == 'data/cache/.state':
                                        entry = zipfile.ZipInfo(name)
                                        entry.external_attr = (kind | 0o777) << 16
                                        archive.writestr(entry, path.read_bytes())
                                    else:
                                        archive.write(path, name)
                        assert invoke('restore', archive_path, root / 'archive-nonregular')[0] != 0
                        assert not (root / 'archive-nonregular').exists()
                    oversized = root / 'oversized.zip'
                    with oversized.open('wb') as stream:
                        stream.truncate(2147483649)
                    assert invoke('restore', oversized, root / 'oversized', digest='sha256:' + '0' * 64)[0] != 0
                    assert not (root / 'oversized').exists()
                    assert invoke('restore', artifact, root / 'wrong-version', 'dc-data-v1-Linux-X64-dc11.0.0_90-1')[0] != 0
                    assert not (root / 'wrong-version').exists()
                    assert invoke('restore', artifact, destination)[0] != 0
                    assert (destination / 'odc.mv.db').read_bytes() == (source / 'odc.mv.db').read_bytes()
                    original = (artifact / 'data' / 'odc.mv.db').read_bytes()
                    for payload in [b'corrupted', b'X' * len(original)]:
                        (artifact / 'data' / 'odc.mv.db').write_bytes(payload)
                        assert invoke('restore', artifact, root / 'corrupt')[0] != 0
                        assert not (root / 'corrupt').exists()
                    (artifact / 'data' / 'odc.mv.db').write_bytes(original)
                    extra = artifact / 'data' / 'unexpected'
                    extra.write_bytes(b'extra')
                    assert invoke('restore', artifact, root / 'extra')[0] != 0
                    extra.unlink()
                    link = source / 'symlink'
                    link.symlink_to(source / 'odc.mv.db')
                    assert invoke('pack', source, root / 'symlink-artifact')[0] != 0
                    link.unlink()
                    manifest = artifact / 'manifest.json'
                    valid = manifest.read_bytes()
                    manifest.write_text('{invalid json')
                    assert invoke('restore', artifact, root / 'invalid')[0] != 0
                    manifest.write_bytes(valid)
                    shutil.rmtree(artifact / 'data' / 'cache')
                    assert invoke('restore', artifact, root / 'incomplete')[0] != 0
                    empty = root / 'empty'
                    empty.mkdir()
                    assert invoke('pack', empty, root / 'empty-artifact')[0] != 0
                    print('Snapshot integrity: service digest, round trip, hidden files, path/type/size rejection, corruption and no-overwrite passed.')
                """.trimIndent())
            }
            val validation = ProcessBuilder("python3", integrity.path, scratch.path, runnerTemp.resolve("security-snapshot.py").path)
                .inheritIO().start()
            check(validation.waitFor(20, TimeUnit.SECONDS)) { "Snapshot integrity controls timed out" }
            check(validation.exitValue() == 0) { "Snapshot integrity controls failed" }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun verifyCompletedSecurityLifecycle() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val security = job(workflow, "security-check")
        check(security.isNotEmpty()) { "Missing independent completed-data security lifecycle" }
        verifyPersistenceProof(workflow, security)
        check(ciCachePolicyViolations(workflow).isEmpty()) { "Actual workflow must satisfy the completed-data policy" }
        val mutations = listOf(
            security to "",
            "    security-check:\n" to "    security-check:\n        needs: [core-quality]\n",
            "    security-check:\n" to "    security-check:\n        if: false\n",
            "    security-check:\n" to "    security-check:\n        continue-on-error: true\n",
            "timeout-minutes: 180" to "timeout-minutes: 90",
            "timeout-minutes: 150" to "timeout-minutes: 90",
            "gradle-home-cache-excludes: dependency-check-data" to "gradle-home-cache-includes: dependency-check-data",
            "dc-data-v1-" to "dc-data-v0-",
            "{version}_" to "unversioned-",
            "{version}_" to "{version}-",
            "{os.environ['RUNNER_ARCH']}" to "any-arch",
            "{os.environ['GITHUB_RUN_ATTEMPT']}" to "1",
            "tomllib.load(catalog)" to "{}",
            "re.fullmatch" to "re.match",
            "unicodedata.category(char)" to "char",
            "dependencyCheckUpdate --info" to "help --info",
            "dependencyCheckAnalyze --info" to "help --info",
            "set -o pipefail" to "set +o pipefail",
            "Verify Updated Security Ownership" to "Omit Updated Security Ownership",
            "Verify Restored Security Ownership" to "Omit Restored Security Ownership",
            "Verify Final Security Ownership" to "Omit Final Security Ownership",
            "NVD_API_KEY: \${{ secrets.NVD_API_KEY }}" to "NVD_API_KEY: unused",
            "steps.security_update.outcome == 'success'" to "steps.security_update.outcome != 'cancelled'",
            "steps.updated_ownership.outcome == 'success'" to "steps.updated_ownership.outcome != 'failure'",
            "steps.restored_ownership.outcome == 'success'" to "steps.restored_ownership.outcome != 'failure'",
            "key: \${{ steps.security_metadata.outputs.key }}" to "key: \${{ steps.security_restore.outputs.cache-matched-key }}",
            "key: \${{ steps.security_restore.outputs.cache-primary-key }}" to "key: \${{ steps.security_restore.outputs.cache-matched-key }}",
            "restore-keys: \${{ steps.security_metadata.outputs.prefix }}" to "restore-keys: dc-data-v1-",
            "!cancelled() && steps.security_update" to "always() && steps.security_update",
            "!cancelled() && steps.security_update" to "steps.security_update",
            "if: steps.filter.outputs.code == 'true' && steps.security_metadata.outputs.eligible == 'true'" to "if: steps.security_restore.outputs.cache-hit != 'true'",
            "find \"\$path\" -xdev ! -user" to "find \"\$path\" -xdev -user",
            "steps.security_restore.outputs.cache-matched-key == ''" to "steps.security_restore.outputs.cache-hit != 'true'",
            "actions: read" to "actions: write",
            "request: {parseSuccessResponseBody: false}" to "request: {parseSuccessResponseBody: true}",
            "archiveSize > 2147483648" to "archiveSize > 8589934592",
            "SNAPSHOT_DIGEST: \${{ steps.security_artifact.outputs.digest }}" to "SNAPSHOT_DIGEST: unverified",
            "retention-days: 14" to "retention-days: 1",
            "include-hidden-files: true" to "include-hidden-files: false",
            "if-no-files-found: error" to "if-no-files-found: ignore",
            "steps.security_artifact_prepare.outcome == 'success'" to "steps.security_artifact_prepare.outcome != 'failure'",
            "steps.security_artifact_download.outcome == 'success'" to "steps.security_artifact_download.outcome != 'failure'",
            "-Dnvd.api.results.per.page=500" to "-Dnvd.api.results.per.page=2000",
            "-Dnvd.api.check.validforhours=0" to "-Dnvd.api.check.validforhours=48",
            "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/" to "https://untrusted.invalid/",
            "python3 \"\$RUNNER_TEMP/security-snapshot.py\" check-records security-update.log" to "true",
            "python3 \"\$RUNNER_TEMP/security-snapshot.py\" check-records security-analysis.log" to "true",
        )
        for ((from, to) in mutations) {
            check(from in workflow) { "Ineffective security mutation: $from" }
            check(ciCachePolicyViolations(workflow.replace(from, to)).isNotEmpty()) { "Unsafe security lifecycle accepted: $from" }
        }
        for (name in listOf("Prepare Security Snapshot Tools", "Select Retained Security Snapshot", "Download Retained Security Snapshot")) {
            val original = step(security, name)
            val ignoredFailure = original.trimEnd() + "\n              continue-on-error: true\n"
            check(ciCachePolicyViolations(workflow.replace(original, ignoredFailure)).isNotEmpty()) { "$name cannot allow integrity/API errors after its script body" }
        }
        val metadata = step(security, "Compute Security Data Metadata")
        val metadataShell = shell(metadata)
        val scratch = Files.createTempDirectory("ci-security-policy-").toFile()
        try {
            val catalog = scratch.resolve("gradle/libs.versions.toml")
            catalog.parentFile.mkdirs()
            val output = scratch.resolve("outputs")
            fun metadataResult(key: String?, version: String = "10.0.4", attempt: String = "1", os: String = "Linux", arch: String = "X64"): Pair<Int, Map<String, String>> {
                catalog.writeText("[versions]\ndependencyCheck = \"$version\"\n")
                output.writeText("")
                val builder = ProcessBuilder("bash", "-e", "-o", "pipefail", "-c", metadataShell).directory(scratch)
                builder.environment().apply {
                    remove("NVD_API_KEY")
                    if (key != null) put("NVD_API_KEY", key)
                    put("GRADLE_USER_HOME", scratch.resolve("actual-gradle-home").path)
                    put("GITHUB_OUTPUT", output.path)
                    put("RUNNER_OS", os)
                    put("RUNNER_ARCH", arch)
                    put("GITHUB_RUN_ID", "123")
                    put("GITHUB_RUN_ATTEMPT", attempt)
                }
                val process = builder.redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Metadata shell timed out" }
                check(key.isNullOrEmpty() || key !in log + output.readText()) { "Metadata exposed key material" }
                return process.exitValue() to output.readLines().filter(String::isNotEmpty).associate { it.substringBefore('=') to it.substringAfter('=') }
            }
            val isolatedSnapshots = listOf(
                metadataResult("normal", "10.0.4"),
                metadataResult("normal", "10.0.4-rc1"),
                metadataResult("normal", os = "Windows"),
                metadataResult("normal", arch = "ARM64"),
            ).map { (exit, outputs) ->
                check(exit == 0) { "Accepted version/platform metadata must succeed" }
                outputs
            }
            check(isolatedSnapshots[1]["prefix"] == "dc-data-v1-Linux-X64-dc10.0.4-rc1_")
            check(isolatedSnapshots[1]["key"] == "dc-data-v1-Linux-X64-dc10.0.4-rc1_123-1")
            for ((index, snapshot) in isolatedSnapshots.withIndex()) {
                val prefix = snapshot.getValue("prefix")
                check(snapshot.getValue("key").startsWith(prefix))
                for ((otherIndex, other) in isolatedSnapshots.withIndex()) {
                    if (index != otherIndex) check(!other.getValue("key").startsWith(prefix)) {
                        "Cross-version/platform cache collision: prefix=$prefix key=${other.getValue("key")}"
                    }
                }
            }
            for (key in listOf(null, "", " \t\r\n", "\u00a0\u2007\u202f", "\u001c\u001f\u2028\u2029", "key-without-logging", "\u0085")) {
                val (exit, outputs) = metadataResult(key)
                check(exit == 0 && outputs["eligible"] == (!key.isNullOrBlank()).toString()) { "Kotlin blank eligibility mismatch" }
                check(outputs["data_dir"] == scratch.resolve("actual-gradle-home/dependency-check-data").path)
                check(outputs["prefix"] == "dc-data-v1-Linux-X64-dc10.0.4_")
                check(outputs["key"] == "dc-data-v1-Linux-X64-dc10.0.4_123-1")
            }
            check(metadataResult("normal", "11.0.0").second["prefix"] == "dc-data-v1-Linux-X64-dc11.0.0_")
            check(metadataResult("normal", attempt = "2").second["key"] == "dc-data-v1-Linux-X64-dc10.0.4_123-2")
            for (version in listOf("../10.0.4", "10.0.4 unsafe", "10.0.4/other", "10.0.4_rc1", "")) check(metadataResult("normal", version).first != 0)
            val home = scratch.resolve("owned-home").apply { mkdirs() }
            home.resolve("dependency-check-data").mkdirs()
            val ownedWorkspace = scratch.resolve("owned-workspace").apply { mkdirs() }
            val runnerTemp = scratch.resolve("runner-temp").apply { mkdirs() }
            fun executeControl(command: String, workspace: String = ownedWorkspace.path, updateOutput: String = "", failPhase: String = ""): Int {
                val process = ProcessBuilder("bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command)
                    .directory(scratch).redirectErrorStream(true).apply {
                        environment()["GITHUB_WORKSPACE"] = workspace
                        environment()["GRADLE_USER_HOME"] = home.path
                        environment()["RUNNER_TEMP"] = runnerTemp.path
                        environment()["UPDATE_OUTPUT"] = updateOutput
                        environment()["FAIL_PHASE"] = failPhase
                    }.start()
                val log = process.inputStream.bufferedReader().readText()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Actual control shell timed out: $log" }
                return process.exitValue()
            }
            check(executeControl(shell(step(security, "Prepare Security Snapshot Tools"))) == 0)
            val callerOwned = scratch.resolve("caller-owned").apply { writeText("owned fixture\n") }
            val callerUid = Files.getAttribute(callerOwned.toPath(), "unix:uid") as Int
            val rejectsCallerOwned = runCatching { foreignOwnershipFixture(callerUid, listOf(callerOwned)) }.isFailure
            val rootOwned = listOf(File("/etc/hostname"), File("/etc/passwd")).firstOrNull {
                it.canRead() && Files.getAttribute(it.toPath(), "unix:uid") == 0
            } ?: error("No readable UID 0 fixture for the modeled ownership-selection control")
            val rootAlternative = if (callerUid == 0) scratch.resolve("foreign-owned").apply {
                writeText("temporary foreign ownership\n")
                Files.setAttribute(toPath(), "unix:uid", 1)
            } else callerOwned
            val rejectsRootOwned = foreignOwnershipFixture(0, listOf(rootOwned, rootAlternative)) == rootAlternative
            check(rejectsCallerOwned && rejectsRootOwned) {
                "Foreign fixture selection: reject caller-owned=$rejectsCallerOwned; UID 0 rejects root-owned=$rejectsRootOwned"
            }
            val foreign = foreignOwnershipFixture(callerUid, listOf(callerOwned, rootAlternative, rootOwned))
            check(Files.getAttribute(foreign.toPath(), "unix:uid") != callerUid)
            for (name in listOf("Verify Restored Security Ownership", "Verify Updated Security Ownership", "Verify Final Security Ownership")) {
                check(executeControl(shell(step(security, name))) == 0) { "Caller-owned paths must pass $name" }
                check(executeControl(shell(step(security, name)), foreign.path) != 0) { "Foreign-owned paths must fail $name" }
            }
            println("Actual ownership shell passed positive/negative controls for UID $callerUid; UID 0 fixture selection checked separately.")
            val gradlew = scratch.resolve("gradlew")
            gradlew.writeText("""
                #!/bin/sh
                printf '%s\n' "${'$'}*" >> actual-argv
                printf '%s\n' "${'$'}{UPDATE_OUTPUT:-}"
                case "${'$'}*" in *nvd.api.datafeed.url=*) phase=seed ;; *) phase=api ;; esac
                if [ "${'$'}{FAIL_PHASE:-}" = all ] || [ "${'$'}{FAIL_PHASE:-}" = "${'$'}phase" ]; then exit 7; fi
            """.trimIndent() + "\n")
            check(gradlew.setExecutable(true))
            val calls = scratch.resolve("actual-argv")
            val database = home.resolve("dependency-check-data/odc.mv.db")
            val command = shell(step(security, "Update Security Data"))
            val base = "dependencyCheckUpdate --info --no-configuration-cache --no-daemon"
            val seed = "$base -Dnvd.api.datafeed.url=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz"
            val api = "$base -Dnvd.api.results.per.page=500"
            val forcedApi = "$api -Dnvd.api.check.validforhours=0"
            fun update(data: String?, failure: String = "", output: String = ""): Pair<Int, List<String>> {
                if (data == null) database.delete() else database.writeText(data)
                calls.writeText("")
                scratch.resolve("security-update.log").writeText("Failed to process CVE-old-run\n")
                val exit = executeControl(command, updateOutput = output, failPhase = failure)
                return exit to calls.readLines()
            }
            check(update("existing database") == (0 to listOf(api))) { "Warm data must use the normal complete API update with bounded pages" }
            check(update(null) == (0 to listOf(seed, forcedApi))) { "Cold bootstrap must seed, then force a live NVD refresh" }
            check(update("") == (0 to listOf(seed, forcedApi))) { "An empty database is not a warm cache" }
            check(update(null, "seed") == (7 to listOf(seed))) { "Failed seed must stop before API refresh" }
            check(update(null, "api") == (7 to listOf(seed, forcedApi))) { "Failed live refresh must remain blocking" }
            check(update("existing database", "api") == (7 to listOf(api))) { "Warm API failure must remain blocking" }
            for (cve in listOf("CVE-2026-6785", "CVE-2026-6786")) {
                val incomplete = "Failed to process $cve\nBUILD SUCCESSFUL"
                val cold = update(null, output = incomplete)
                check(cold.first != 0 && cold.second == listOf(seed)) { "Dropped $cve must stop before API refresh or publication" }
                check(update("existing database", output = incomplete).first != 0) { "A successful updater exit must not publish data after dropping $cve" }
            }
            check(update("existing database", output = "Completed processing batch 1/1 (100%)").first == 0)
            for ((failure, expected) in listOf("" to 0, "all" to 7)) {
                calls.writeText("")
                check(executeControl(shell(step(security, "Run Security Check")), failPhase = failure) == expected)
                check(calls.readLines() == listOf("dependencyCheckAnalyze --info --no-configuration-cache --no-daemon -Dnvd.api.results.per.page=500")) {
                    "An implicit analysis refresh must also use bounded NVD API pages"
                }
            }
            check(executeControl(shell(step(security, "Run Security Check")), updateOutput = "Failed to process CVE-2026-6785\nBUILD SUCCESSFUL") != 0) {
                "An implicit NVD refresh during analysis must not silently drop a CVE"
            }
        } finally {
            scratch.deleteRecursively()
        }
        val join = shell(step(job(workflow, "build-test-publish"), "Require Core and Mutation Success"))
        val results = listOf("success", "failure", "cancelled", "skipped", "")
        for (core in results) for (mutation in results) for (securityResult in results) {
            val command = join.replace("\${{ needs.core-quality.result }}", core)
                .replace("\${{ needs.mutation-tests.result }}", mutation).replace("\${{ needs.security-check.result }}", securityResult)
                .replace("\${{ needs.build-logic-validation.result }}", "success")
                .replace("\${{ needs.go-validation.result }}", "success")
            check("\${{" !in command) { "Unresolved security join expression" }
            check(shellSucceeds(command) == (core == "success" && mutation == "success" && securityResult == "success")) {
                "Actual 125-way join failed: $core/$mutation/$securityResult"
            }
        }
        fun condition(name: String, code: Boolean, eligible: Boolean, cancelled: Boolean, update: String, owned: String, restored: String = "success", priorSuccess: Boolean = true, prepared: String = "success"): Boolean {
            var expression = step(security, name).lineSequence().map(String::trim).first { it.startsWith("if: ") }.removePrefix("if: ")
                .removePrefix("\${{ ").removeSuffix(" }}")
            for ((from, to) in mapOf(
                "steps.filter.outputs.code" to "'$code'", "steps.security_metadata.outputs.eligible" to "'$eligible'",
                "steps.security_update.outcome" to "'$update'", "steps.updated_ownership.outcome" to "'$owned'",
                "steps.restored_ownership.outcome" to "'$restored'", "!cancelled()" to "'$cancelled' != 'true'", "always()" to "'true' == 'true'",
                "steps.security_artifact_prepare.outcome" to "'$prepared'",
            )) expression = expression.replace(from, to)
            val hasStatus = "cancelled()" in step(security, name) || "always()" in step(security, name) || "success()" in step(security, name)
            return (hasStatus || priorSuccess) && shellSucceeds("[[ $expression ]]")
        }
        for (code in listOf(false, true)) for (eligible in listOf(false, true)) for (cancelled in listOf(false, true)) {
            for (update in results) for (owned in results) {
                val expected = code && eligible && !cancelled && update == "success" && owned == "success"
                for (priorSuccess in listOf(false, true)) {
                    for (name in listOf("Prepare Completed Security Artifact", "Upload Completed Security Artifact", "Require Retained Security Snapshot", "Save Completed Security Data", "Look Up Completed Security Snapshot", "Require Completed Security Snapshot", "Run Security Check")) {
                        check(condition(name, code, eligible, cancelled, update, owned, priorSuccess = priorSuccess) == expected) { "Actual $name condition failed" }
                    }
                }
            }
        }
        for (restored in results) check(condition("Update Security Data", true, true, false, "", "", restored) == (restored == "success"))
        for (prepared in results) {
            check(condition("Upload Completed Security Artifact", true, true, false, "success", "success", prepared = prepared) == (prepared == "success")) {
                "Incomplete preparation must never publish a snapshot"
            }
        }
        for (failedStep in listOf("Prepare Completed Security Artifact", "Upload Completed Security Artifact", "Require Retained Security Snapshot", "Save Completed Security Data", "Look Up Completed Security Snapshot", "Require Completed Security Snapshot")) {
            for (analysis in listOf("success", "failure")) {
                check(condition("Run Security Check", true, true, false, "success", "success", priorSuccess = false)) { "$failedStep failure must not prevent $analysis analysis" }
                check("continue-on-error:" !in step(security, failedStep)) { "$failedStep failure must not be allowed" }
            }
        }
        println("Actual security metadata, eligibility, lifecycle conditions and all 125 join triples passed (no scanner/network).")
    }

    private fun verifyPersistenceProof(workflow: String, security: String) {
        val artifactAssertion = shell(step(security, "Require Retained Security Snapshot"))
        check(artifactAssertion.isNotBlank()) { "Missing retained-snapshot publication assertion" }
        for (id in listOf("1", "1234567890123")) check(shellSucceeds(artifactAssertion, mapOf("ARTIFACT_ID" to id)))
        for (id in listOf("", "0", "-1", "true", "123x", "123\n456")) {
            check(!shellSucceeds(artifactAssertion, mapOf("ARTIFACT_ID" to id))) { "Snapshot publication accepted invalid ID '$id'" }
        }
        check(!shellSucceeds("unset ARTIFACT_ID\n$artifactAssertion"))
        val lookup = step(security, "Look Up Completed Security Snapshot")
        val assertion = step(security, "Require Completed Security Snapshot")
        check(lookup.isNotEmpty() && assertion.isNotEmpty()) { "Missing exact completed-snapshot lookup and literal-true assertion" }
        val assertionShell = shell(assertion)
        check(shellSucceeds(assertionShell, mapOf("CACHE_HIT" to "true")))
        for (value in listOf("", "false", "True", "TRUE", "true ", "nonexact")) {
            check(!shellSucceeds(assertionShell, mapOf("CACHE_HIT" to value))) { "Snapshot assertion accepted nonexact hit '$value'" }
        }
        check(!shellSucceeds("unset CACHE_HIT\n$assertionShell")) { "Snapshot assertion accepted unset output" }
        val proofMutations = listOf(
            lookup to "",
            assertion to "",
            lookup to lookup.replace("steps.security_restore.outputs.cache-primary-key", "steps.security_restore.outputs.cache-matched-key"),
            lookup to lookup.replace("steps.security_metadata.outputs.data_dir", "github.workspace"),
            lookup to lookup.replace("lookup-only: true", "lookup-only: false"),
            lookup to lookup.replace("fail-on-cache-miss: true", "fail-on-cache-miss: false"),
            lookup to lookup.replace("fail-on-cache-miss: true", "fail-on-cache-miss: true\n                  restore-keys: dc-data-v1-"),
            lookup to lookup.replace("!cancelled()", "success()"),
            lookup to lookup.replace("!cancelled()", "!cancelled() && steps.security_save.outcome == 'success'"),
            assertion to assertion.replace("!cancelled()", "success()"),
            assertion to assertion.replace("!cancelled()", "!cancelled() && steps.security_snapshot_lookup.outcome == 'success'"),
            assertion to assertion.replace("= true", "!= false"),
            assertion to assertion.replace("CACHE_HIT: \${{ steps.security_snapshot_lookup.outputs.cache-hit }}", "CACHE_HIT: true"),
            assertion to assertion.replace("run: test", "continue-on-error: true\n              run: test"),
        )
        for ((from, to) in proofMutations) {
            check(from != to && from in workflow) { "Ineffective persistence-proof mutation" }
            check(ciCachePolicyViolations(workflow.replace(from, to)).isNotEmpty()) { "Unsafe/missing completed-snapshot proof accepted" }
        }
        println("Actual snapshot assertion accepts only literal true; absent/wrong/skipped persistence proof rejected (no real cache calls).")
    }

    private fun foreignOwnershipFixture(callerUid: Int, candidates: List<File>): File =
        candidates.firstOrNull { it.isFile && it.canRead() && Files.getAttribute(it.toPath(), "unix:uid") != callerUid }
            ?: error("No readable foreign-owned fixture for UID $callerUid; ownership controls cannot run in this namespace")

    private fun job(workflow: String, name: String): String =
        Regex("(?ms)^    $name:\\n.*?(?=^    [a-zA-Z0-9_-]+:|\\z)").find(workflow)?.value.orEmpty()

    private fun step(job: String, name: String): String = job.substringAfter("- name: $name\n", "").substringBefore("\n            - name: ")

    private fun shell(step: String): String = step.substringAfter("run: |\n", "").let { block ->
        if (block.isEmpty()) step.substringAfter("run: ").substringBefore('\n').trim()
        else block.lineSequence().takeWhile { it.isBlank() || it.startsWith("                  ") }.joinToString("\n") { it.removePrefix("                  ") }
    }
}
