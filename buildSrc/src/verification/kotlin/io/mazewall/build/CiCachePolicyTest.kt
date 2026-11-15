package io.mazewall.build

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Public JVM entrypoint is consumed by the mandatory Java verification harness. */
object CiCachePolicyTest {
    @JvmStatic
    fun run() {
        verifyDecoupledSecurityContract()
        verifyCoreIntegrationCompilationOverlay()
        verifyOptionalJoin()
        verifyOptionalDiagnostics()
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
                                      - '.github/workflows/security-data.yml'
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
                        - name: Resolve Core Integration Compilation Cache
                          id: core_compile_cache
                          run: |
                              cache_home=${'$'}(./scripts/run_containerized_tests.sh --print-cache-home)
                              test -n "${'$'}cache_home"
                              printf 'path=%s/caches/build-cache-1\n' "${'$'}cache_home" >> "${'$'}GITHUB_OUTPUT"
                        - name: Restore Core Integration Compilation Cache
                          if: github.event_name == 'pull_request' && steps.core_compile_cache.outcome == 'success' && steps.core_compile_cache.outputs.path != ''
                          uses: actions/cache/restore@v4
                          with:
                              path: ${'$'}{{ steps.core_compile_cache.outputs.path }}
                              key: core-integration-compile-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}
                              restore-keys: |
                                  core-integration-compile-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-
                        - name: Save Container Gradle Cache (Master Only)
                          uses: actions/cache/save@v4
                          if: always() && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master') && steps.container_cache_key.outcome == 'success'
                          with:
                              key: container-gradle-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}
                        - name: Verify Workspace and Cache Ownership
                          id: core_ownership
                          if: always()
                          run: :
                        - name: Save Core Integration Compilation Cache
                          if: always() && github.event_name == 'pull_request' && steps.core_ownership.outcome == 'success' && steps.container_cache_key.outcome == 'success' && steps.core_compile_cache.outcome == 'success' && steps.core_compile_cache.outputs.path != ''
                          uses: actions/cache/save@v4
                          with:
                              path: ${'$'}{{ steps.core_compile_cache.outputs.path }}
                              key: core-integration-compile-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}
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
                                      - '.github/workflows/security-data.yml'
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
            val actualWorkflow = repository.resolve(".github/workflows/ci.yml").readText()
            val actualSecurity = job(actualWorkflow, "security-check")
            val actualBuildLogic = job(actualWorkflow, "build-logic-validation")
            val actualGo = job(actualWorkflow, "go-validation")
            val optionalUpload = "            - name: Upload Optional JUnit Diagnostics\n" +
                step(job(actualWorkflow, "optional-integrations"), "Upload Optional JUnit Diagnostics").trimEnd()
            val fixtureFilter = step(job(legacy, "core-quality"), "Check file changes").trimEnd()
            val securityFixture = actualSecurity.replace(step(actualSecurity, "Check file changes").trimEnd(), fixtureFilter)
            legacy
                .replace("    core-quality:\n", optionalUpload + "\n    core-quality:\n")
                .replace("    optional-integrations:\n", securityFixture.trimEnd() + "\n" + actualBuildLogic.trimEnd() + "\n" + actualGo.trimEnd() + "\n    optional-integrations:\n")
                .replace("needs: [core-quality, mutation-tests]", "needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation, optional-integrations, confined-helper-kernel]")
                .replace(
                    "test \"\${{ needs.mutation-tests.result }}\" = success",
                    "test \"\${{ needs.mutation-tests.result }}\" = success && test \"\${{ needs.security-check.result }}\" = success && test \"\${{ needs.build-logic-validation.result }}\" = success && test \"\${{ needs.go-validation.result }}\" = success && test \"\${{ needs.optional-integrations.result }}\" = success && test \"\${{ needs.confined-helper-kernel.result }}\" = success",
                )
        }
        val failures = mutableListOf<String>()
        fun expectViolations(label: String, workflow: String, vararg expected: String) {
            val actual = ciCachePolicyViolations(workflow).map { it.name }
            if (actual != expected.toList()) failures += "$label: expected ${expected.toList()}, got $actual"
        }
        expectViolations("independent bounded graph", valid)
        expectViolations("PR cache writes", valid.replace("cache-read-only: false", "cache-read-only: true"), "PR_CACHE_WRITES", "BUILD_LOGIC_VALIDATION")
        expectViolations("Dependency-Check data", valid.replace("dependency-check-data", "caches"), "DEPENDENCY_CHECK_DATA")
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
            "needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation, optional-integrations, confined-helper-kernel]" to "needs: [core-quality]",
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
                    .replace("${'$'}{{ needs.optional-integrations.result }}", "success")
                    .replace("${'$'}{{ needs.confined-helper-kernel.result }}", "success")
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
        println("CI policy: cache/graph regressions, required security/build-logic/Go/integration checks, join projections, default-push publication and PR-only cancellation passed.")
    }

    private fun verifyCoreIntegrationCompilationOverlay() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val core = job(workflow, "core-quality")
        val restore = step(core, "Restore Core Integration Compilation Cache")
        val save = step(core, "Save Core Integration Compilation Cache")
        check("github.event_name == 'pull_request'" in restore) { "Core compilation restore must be PR-only" }
        val resolve = step(core, "Resolve Core Integration Compilation Cache")
        check("steps.core_compile_cache.outputs.path" in restore && "caches/build-cache-1" in resolve) {
            "Core compilation restore must target only the discovered Gradle build cache"
        }
        check("core-integration-compile-" in restore && "steps.container_cache_key.outputs.hash" in restore) {
            "Core compilation restore must use the tracked container identity"
        }
        check("github.event_name == 'pull_request'" in save && "steps.core_ownership.outcome == 'success'" in save &&
            "steps.core_compile_cache.outcome == 'success'" in save && "steps.container_cache_key.outcome == 'success'" in save) {
            "Core compilation save must be PR-only and require discovery, ownership and identity"
        }
        check("~/.cache/mazewall/container-gradle" !in restore && "container-gradle-home" !in restore) {
            "Core compilation restore must not archive the whole container Gradle home"
        }
        check(core.indexOf("- name: Restore Container Gradle Cache") < core.indexOf("- name: Resolve Core Integration Compilation Cache") &&
            core.indexOf("- name: Setup Podman Socket") < core.indexOf("- name: Resolve Core Integration Compilation Cache") &&
            core.indexOf("- name: Resolve Core Integration Compilation Cache") < core.indexOf("- name: Restore Core Integration Compilation Cache")) {
            "Core compilation overlay must follow the whole-home restore and the selected Podman engine"
        }
        for ((from, to) in listOf(
            "github.event_name == 'pull_request'" to "github.event_name == 'push'",
            "caches/build-cache-1" to "caches",
            "id: core_ownership" to "id: wrong_ownership",
            "steps.core_ownership.outcome == 'success'" to "true",
            "steps.core_compile_cache.outcome == 'success'" to "true",
        )) {
            val mutated = workflow.replace(from, to)
            check(mutated != workflow && CiCacheViolation.CORE_COMPILATION_CACHE in ciCachePolicyViolations(mutated)) {
                "Unsafe core compilation cache mutation accepted: $from"
            }
        }
    }

    private fun verifyOptionalJoin() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val join = shell(step(job(workflow, "build-test-publish"), "Require Core and Mutation Success"))
        for (result in listOf("success", "failure", "cancelled", "skipped", "")) {
            var command = join
            for (name in listOf("core-quality", "mutation-tests", "security-check", "build-logic-validation", "go-validation", "confined-helper-kernel")) {
                command = command.replace("\${{ needs.$name.result }}", "success")
            }
            command = command.replace("\${{ needs.optional-integrations.result }}", result)
            check("\${{" !in command && shellSucceeds(command) == (result == "success")) {
                "Integration result must be required by the actual final gate: '$result'"
            }
        }
        println("Actual optional-integration result gate rejects failure, cancellation, skipped and missing results.")
    }

    private fun verifyOptionalDiagnostics() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val upload = step(job(workflow, "optional-integrations"), "Upload Optional JUnit Diagnostics")
        for (required in listOf(
                "if: always()",
                "build/reports/ci-resources/optional-adapters-cache.log",
                "build/reports/optional-integrations/run-*/*.log",
                "build/reports/optional-integrations/run-*/*.command.json",
                "build/reports/optional-integrations/run-*/*-reports/**",
            )) {
            val mutated = workflow.replace(upload, upload.replace(required, "missing"))
            check(mutated != workflow && ciCachePolicyViolations(mutated).any { it.name == "OPTIONAL_DIAGNOSTICS" }) {
                "Missing failure diagnostics must be refused: $required"
            }
        }
    }

    private fun verifyBuildLogicLane() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val workflow = repository.resolve(".github/workflows/ci.yml").readText()
        val lane = job(workflow, "build-logic-validation")
        check(lane.isNotEmpty()) { "Missing independent build-logic-validation lane" }
        check(ciCachePolicyViolations(workflow).isEmpty()) { "Actual workflow policy violations: ${ciCachePolicyViolations(workflow)}" }
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
            ":consumerBuildLogicTest" to ":help",
            "Run Consumer Build Logic Regressions" to "Omitted Consumer Regressions",
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
            "build/test-results/consumerBuildLogicTest/" to "",
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
                .replace("\${{ needs.optional-integrations.result }}", "success")
                .replace("\${{ needs.confined-helper-kernel.result }}", "success")
            check("\${{" !in command) { "Unresolved four-way join expression" }
            check(shellSucceeds(command) == (core == "success" && mutation == "success" && security == "success" && buildLogic == "success")) {
                "Actual 625-way join failed: $core/$mutation/$security/$buildLogic"
            }
            checked++
        }
        check(checked == 625)
        val prerequisites = listOf("core-quality", "mutation-tests", "security-check", "build-logic-validation", "go-validation", "optional-integrations", "confined-helper-kernel")
        for (prerequisite in prerequisites) {
            val removedNeed = workflow.replace(
                "needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation, optional-integrations, confined-helper-kernel]",
                "needs: [${prerequisites.filter { it != prerequisite }.joinToString(", ")}]",
            )
            check(removedNeed != workflow && ciCachePolicyViolations(removedNeed).isNotEmpty()) { "Missing join prerequisite accepted: $prerequisite" }
            val removedResult = workflow.replace(" && test \"\${{ needs.$prerequisite.result }}\" = success", "")
                .replace("test \"\${{ needs.$prerequisite.result }}\" = success && ", "")
            check(removedResult != workflow && ciCachePolicyViolations(removedResult).isNotEmpty()) { "Missing literal success accepted: $prerequisite" }
        }
        for (result in results) {
            var command = join
            for (name in prerequisites.filter { it != "confined-helper-kernel" }) {
                command = command.replace("\${{ needs.$name.result }}", "success")
            }
            command = command.replace("\${{ needs.confined-helper-kernel.result }}", result)
            check("\${{" !in command && shellSucceeds(command) == (result == "success")) { "Private lane result must be required: $result" }
        }
        println("Actual four-result projection: 625 tuples with Go/integration success; unsafe build-logic lanes rejected.")
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
            for (name in listOf("core-quality", "mutation-tests", "security-check", "build-logic-validation", "optional-integrations", "confined-helper-kernel")) {
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

    private fun verifyDecoupledSecurityContract() {
        val repository = File(System.getProperty("user.dir")).let { if (it.name == "buildSrc") it.parentFile else it }
        val updaterFile = repository.resolve(".github/workflows/security-data.yml")
        check(updaterFile.isFile) { "Missing trusted security data updater" }
        val updater = updaterFile.readText()
        val ci = repository.resolve(".github/workflows/ci.yml").readText()
        val security = job(ci, "security-check")
        check("workflow_dispatch:" in updater && "schedule:" in updater)
        check("github.event.repository.default_branch" in updater)
        check("cancel-in-progress: false" in updater)
        check("-Pmazewall.securitySnapshotUpdate=true" in updater && "nvd.api.results.per.page=500" in updater)
        check("check-records security-update.log" in updater && "verifySecuritySnapshot" in updater)
        check("!cancelled()" in updater && "actions/upload-artifact@" in updater)
        check("actions/cache/save@" in updater && "lookup-only: true" in updater)
        val filter = step(security, "Check file changes")
        check("- '.github/workflows/security-data.yml'" in filter) { "Updater changes must trigger security validation" }
        val controls = step(security, "Test Security Snapshot Controls")
        check("python3 scripts/test_security_snapshot.py" in controls && "steps.filter.outputs.code == 'true'" in controls)
        check(ciCachePolicyViolations(ci.replace("- '.github/workflows/security-data.yml'", "- 'README.md'")).isNotEmpty())
        check(ciCachePolicyViolations(ci.replace("python3 scripts/test_security_snapshot.py", "true")).isNotEmpty())
        check("dependencyCheckUpdate" !in security && "NVD_API_KEY" !in security)
        check("verifySecuritySnapshot" in security && "-Pmazewall.securitySnapshotScan=true" in security)
        check("dependencyCheckAnalyze" in security && "actions/cache/restore@" !in security)
        check("getWorkflowRunAttempt" in security && "getWorkflow" in security)
        check("security-data.yml" in security && "ci.yml" in security)
        check(securityUpdaterPolicyViolations(updater).isEmpty()) { "Valid updater rejected" }
        for (required in listOf(
            "schedule:", "workflow_dispatch:", "github.event.repository.default_branch", "cancel-in-progress: false",
            "-Pmazewall.securitySnapshotUpdate=true", "check-records security-update.log", "verifySecuritySnapshot",
            "!cancelled()", "steps.security_update.outcome == 'success'", "steps.updated_ownership.outcome == 'success'",
            "steps.verified_snapshot.outcome == 'success'", "steps.security_artifact_prepare.outcome == 'success'",
            "lookup-only: true", "fail-on-cache-miss: true", "test \"${'$'}{CACHE_HIT:-}\" = true",
        )) {
            check(securityUpdaterPolicyViolations(updater.replace(required, "removed-contract")).isNotEmpty()) {
                "Updater policy accepted removal of $required"
            }
        }
        for (required in listOf("verifySecuritySnapshot", "-Pmazewall.securitySnapshotScan=true")) {
            check(ciCachePolicyViolations(ci.replace(required, "removed-security-contract")).isNotEmpty()) {
                "Security policy accepted removal of $required"
            }
        }
    }

    private fun job(workflow: String, name: String): String =
        Regex("(?ms)^    $name:\\n.*?(?=^    [a-zA-Z0-9_-]+:|\\z)").find(workflow)?.value.orEmpty()

    private fun step(job: String, name: String): String = job.substringAfter("- name: $name\n", "").substringBefore("\n            - name: ")

    private fun shell(step: String): String = step.substringAfter("run: |\n", "").let { block ->
        if (block.isEmpty()) step.substringAfter("run: ").substringBefore('\n').trim()
        else block.lineSequence().takeWhile { it.isBlank() || it.startsWith("                  ") }.joinToString("\n") { it.removePrefix("                  ") }
    }
}
