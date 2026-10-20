package io.mazewall.build

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Public JVM entrypoint is consumed by the mandatory Java verification harness. */
object CiCachePolicyTest {
    @JvmStatic
    fun run() {
        verifyCompletedSecurityLifecycle()
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
            val fixtureFilter = step(job(legacy, "core-quality"), "Check file changes").trimEnd()
            val securityFixture = actualSecurity.replace(step(actualSecurity, "Check file changes").trimEnd(), fixtureFilter)
            legacy.replace("    optional-integrations:\n", securityFixture.trimEnd() + "\n    optional-integrations:\n")
                .replace("needs: [core-quality, mutation-tests]", "needs: [core-quality, mutation-tests, security-check]")
                .replace("test \"\${{ needs.mutation-tests.result }}\" = success", "test \"\${{ needs.mutation-tests.result }}\" = success && test \"\${{ needs.security-check.result }}\" = success")
        }
        val failures = mutableListOf<String>()
        fun expectViolations(label: String, workflow: String, vararg expected: String) {
            val actual = ciCachePolicyViolations(workflow).map { it.name }
            if (actual != expected.toList()) failures += "$label: expected ${expected.toList()}, got $actual"
        }
        expectViolations("independent bounded graph", valid)
        expectViolations("PR cache writes", valid.replace("cache-read-only: false", "cache-read-only: true"), "PR_CACHE_WRITES")
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
            "needs: [core-quality, mutation-tests, security-check]" to "needs: [core-quality]",
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
        println("CI policy: legacy cache/graph regressions, independent security lifecycle, 125 actual join triples, default-push publication and PR-only cancellation passed.")
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
            "{version}-" to "unversioned-",
            "{os.environ['RUNNER_ARCH']}" to "any-arch",
            "{os.environ['GITHUB_RUN_ATTEMPT']}" to "1",
            "tomllib.load(catalog)" to "{}",
            "re.fullmatch" to "re.match",
            "unicodedata.category(char)" to "char",
            "dependencyCheckUpdate --info" to "help --info",
            "dependencyCheckAnalyze --info" to "help --info",
            "set -o pipefail\n                  ./gradlew dependencyCheckUpdate" to "./gradlew dependencyCheckUpdate",
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
        )
        for ((from, to) in mutations) {
            check(from in workflow) { "Ineffective security mutation: $from" }
            check(ciCachePolicyViolations(workflow.replace(from, to)).isNotEmpty()) { "Unsafe security lifecycle accepted: $from" }
        }
        val metadata = step(security, "Compute Security Data Metadata")
        val metadataShell = shell(metadata)
        val scratch = Files.createTempDirectory("ci-security-policy-").toFile()
        try {
            val catalog = scratch.resolve("gradle/libs.versions.toml")
            catalog.parentFile.mkdirs()
            val output = scratch.resolve("outputs")
            fun metadataResult(key: String?, version: String = "10.0.4", attempt: String = "1"): Pair<Int, Map<String, String>> {
                catalog.writeText("[versions]\ndependencyCheck = \"$version\"\n")
                output.writeText("")
                val builder = ProcessBuilder("bash", "-e", "-o", "pipefail", "-c", metadataShell).directory(scratch)
                builder.environment().apply {
                    remove("NVD_API_KEY")
                    if (key != null) put("NVD_API_KEY", key)
                    put("GRADLE_USER_HOME", scratch.resolve("actual-gradle-home").path)
                    put("GITHUB_OUTPUT", output.path)
                    put("RUNNER_OS", "Linux")
                    put("RUNNER_ARCH", "X64")
                    put("GITHUB_RUN_ID", "123")
                    put("GITHUB_RUN_ATTEMPT", attempt)
                }
                val process = builder.redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Metadata shell timed out" }
                check(key.isNullOrEmpty() || key !in log + output.readText()) { "Metadata exposed key material" }
                return process.exitValue() to output.readLines().filter(String::isNotEmpty).associate { it.substringBefore('=') to it.substringAfter('=') }
            }
            for (key in listOf(null, "", " \t\r\n", "\u00a0\u2007\u202f", "\u001c\u001f\u2028\u2029", "key-without-logging", "\u0085")) {
                val (exit, outputs) = metadataResult(key)
                check(exit == 0 && outputs["eligible"] == (!key.isNullOrBlank()).toString()) { "Kotlin blank eligibility mismatch" }
                check(outputs["data_dir"] == scratch.resolve("actual-gradle-home/dependency-check-data").path)
                check(outputs["prefix"] == "dc-data-v1-Linux-X64-dc10.0.4-")
                check(outputs["key"] == "dc-data-v1-Linux-X64-dc10.0.4-123-1")
            }
            check(metadataResult("normal", "11.0.0").second["prefix"] == "dc-data-v1-Linux-X64-dc11.0.0-")
            check(metadataResult("normal", attempt = "2").second["key"] == "dc-data-v1-Linux-X64-dc10.0.4-123-2")
            for (version in listOf("../10.0.4", "10.0.4 unsafe", "10.0.4/other", "")) check(metadataResult("normal", version).first != 0)
            val home = scratch.resolve("owned-home").apply { mkdirs() }
            home.resolve("dependency-check-data").mkdirs()
            fun executeControl(command: String, workspace: String = scratch.path): Int {
                val process = ProcessBuilder("bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command)
                    .directory(scratch).redirectErrorStream(true).apply {
                        environment()["GITHUB_WORKSPACE"] = workspace
                        environment()["GRADLE_USER_HOME"] = home.path
                    }.start()
                val log = process.inputStream.bufferedReader().readText()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Actual control shell timed out: $log" }
                return process.exitValue()
            }
            for (name in listOf("Verify Restored Security Ownership", "Verify Updated Security Ownership", "Verify Final Security Ownership")) {
                check(executeControl(shell(step(security, name))) == 0) { "Caller-owned paths must pass $name" }
                check(executeControl(shell(step(security, name)), "/etc/hostname") != 0) { "Foreign-owned paths must fail $name" }
            }
            val gradlew = scratch.resolve("gradlew")
            for (exit in listOf(0, 7)) {
                gradlew.writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" > actual-argv\nexit $exit\n")
                check(gradlew.setExecutable(true))
                check(executeControl(shell(step(security, "Update Security Data"))) == exit) { "Actual updater pipe must preserve exit $exit" }
                check(scratch.resolve("actual-argv").readText().trim() == "dependencyCheckUpdate --info --no-configuration-cache --no-daemon")
                check(executeControl(shell(step(security, "Run Security Check"))) == exit) { "Actual analyzer must preserve exit $exit" }
                check(scratch.resolve("actual-argv").readText().trim() == "dependencyCheckAnalyze --info --no-configuration-cache --no-daemon")
            }
        } finally {
            scratch.deleteRecursively()
        }
        val join = shell(step(job(workflow, "build-test-publish"), "Require Core and Mutation Success"))
        val results = listOf("success", "failure", "cancelled", "skipped", "")
        for (core in results) for (mutation in results) for (securityResult in results) {
            val command = join.replace("\${{ needs.core-quality.result }}", core)
                .replace("\${{ needs.mutation-tests.result }}", mutation).replace("\${{ needs.security-check.result }}", securityResult)
            check(shellSucceeds(command) == (core == "success" && mutation == "success" && securityResult == "success")) {
                "Actual 125-way join failed: $core/$mutation/$securityResult"
            }
        }
        fun condition(name: String, code: Boolean, eligible: Boolean, cancelled: Boolean, update: String, owned: String, restored: String = "success", priorSuccess: Boolean = true): Boolean {
            var expression = step(security, name).lineSequence().map(String::trim).first { it.startsWith("if: ") }.removePrefix("if: ")
                .removePrefix("\${{ ").removeSuffix(" }}")
            for ((from, to) in mapOf(
                "steps.filter.outputs.code" to "'$code'", "steps.security_metadata.outputs.eligible" to "'$eligible'",
                "steps.security_update.outcome" to "'$update'", "steps.updated_ownership.outcome" to "'$owned'",
                "steps.restored_ownership.outcome" to "'$restored'", "!cancelled()" to "'$cancelled' != 'true'", "always()" to "'true' == 'true'",
            )) expression = expression.replace(from, to)
            val hasStatus = "cancelled()" in step(security, name) || "always()" in step(security, name) || "success()" in step(security, name)
            return (hasStatus || priorSuccess) && shellSucceeds("[[ $expression ]]")
        }
        for (code in listOf(false, true)) for (eligible in listOf(false, true)) for (cancelled in listOf(false, true)) {
            for (update in results) for (owned in results) {
                val expected = code && eligible && !cancelled && update == "success" && owned == "success"
                for (priorSuccess in listOf(false, true)) {
                    for (name in listOf("Save Completed Security Data", "Look Up Completed Security Snapshot", "Require Completed Security Snapshot", "Run Security Check")) {
                        check(condition(name, code, eligible, cancelled, update, owned, priorSuccess = priorSuccess) == expected) { "Actual $name condition failed" }
                    }
                }
            }
        }
        for (restored in results) check(condition("Update Security Data", true, true, false, "", "", restored) == (restored == "success"))
        for (failedStep in listOf("Save Completed Security Data", "Look Up Completed Security Snapshot", "Require Completed Security Snapshot")) {
            for (analysis in listOf("success", "failure")) {
                check(condition("Run Security Check", true, true, false, "success", "success", priorSuccess = false)) { "$failedStep failure must not prevent $analysis analysis" }
                check("continue-on-error:" !in step(security, failedStep)) { "$failedStep failure must not be allowed" }
            }
        }
        println("Actual security metadata, eligibility, lifecycle conditions and all 125 join triples passed (no scanner/network).")
    }

    private fun verifyPersistenceProof(workflow: String, security: String) {
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

    private fun job(workflow: String, name: String): String =
        Regex("(?ms)^    $name:\\n.*?(?=^    [a-zA-Z0-9_-]+:|\\z)").find(workflow)?.value.orEmpty()

    private fun step(job: String, name: String): String = job.substringAfter("- name: $name\n", "").substringBefore("\n            - name: ")

    private fun shell(step: String): String = step.substringAfter("run: |\n", "").let { block ->
        if (block.isEmpty()) step.substringAfter("run: ").substringBefore('\n').trim()
        else block.lineSequence().takeWhile { it.isBlank() || it.startsWith("                  ") }.joinToString("\n") { it.removePrefix("                  ") }
    }
}
