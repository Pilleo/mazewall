package io.mazewall.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

internal enum class CiCacheViolation(val explanation: String) {
    PR_CACHE_WRITES("PR reruns must save merge-ref-scoped Gradle state"),
    DEPENDENCY_CHECK_DATA("Only the explicit compatible completed-data cache may own Dependency-Check data"),
    SECURITY_LIFECYCLE("Independent security must restore owned data, update successfully, save completed data and always analyze unless cancelled"),
    HOST_DEPENDENCY_CHECK("Dependency-Check must use host Gradle User Home without configuration cache"),
    CONTAINER_DEPENDENCY_CHECK("Dependency-Check must not cross the host/container cache boundary"),
    IMMUTABLE_NVD_CACHE("An immutable daily NVD key can pin stale data"),
    CONTAINER_CACHE_IDENTITY("Both jobs must compute one tracked-input identity immediately after checkout and reuse its output"),
    CONTAINER_CACHE_SAVE("Container cache saves must require successful identity computation"),
    CONTAINER_CACHE_REUSE("Main verification must try compatible optional PR caches before the broad main fallback"),
    CORE_COMPILATION_CACHE("Core PR reruns must overlay only the discovered container Gradle build-cache subtree after the whole-home restore"),
    SERIAL_VALIDATION("Optional, core and mutation validation must run independently without allowing failure"),
    VALIDATION_RUNNER("All three validation lanes must use ubuntu-26.04 and JDK 25"),
    CODE_CHANGE_FILTER("Core and mutation need unconditional PR-readable code filters including the CI workflow"),
    MUTATION_EXECUTION("Run all three PIT tasks once, serially through the host resource probe with bounded workers and forks"),
    MUTATION_DIAGNOSTICS("Always upload the mutation resource receipt and all three PIT reports"),
    OPTIONAL_DIAGNOSTICS("Always retain optional command logs and copied-consumer test reports, including failures"),
    BUILD_LOGIC_VALIDATION("Independent read-only build logic must run with strict configuration cache, bounded resources and always-uploaded diagnostics"),
    GO_VALIDATION("Opt-in Go tests and pinned vulnerability scanning must stay flag-gated, bounded and fail closed for both modules"),
    REQUIRED_CHECK_JOIN("The unchanged required check must always evaluate and require core, mutation, security, build-logic, optional-integration, confined-helper and enabled Go validation success"),
    PUBLISH_GRAPH("Publication must run only after both validations succeed on a default-branch push"),
    PACKAGE_WRITE_SCOPE("Only the publication job may have package-write permission"),
    PR_RUN_CONCURRENCY("Only superseded PR runs may cancel; push runs need separate run-ID groups"),
}

internal fun ciCachePolicyViolations(workflow: String): List<CiCacheViolation> {
    val validationNames = listOf("Koog and Spring Integration Acceptance", "Core Quality and Kernel Validation", "Mutation Validation")
    val validationJobs = listOf("optional-integrations", "core-quality", "mutation-tests").map { workflowJob(workflow, it) }
    val containerJobs = validationJobs.take(2)
    val mutation = validationJobs[2]
    val security = workflowJob(workflow, "security-check")
    val join = workflowJob(workflow, "build-test-publish")
    return CiCacheViolation.entries.filter { violation ->
        when (violation) {
            CiCacheViolation.PR_CACHE_WRITES -> "cache-read-only: false" !in workflow ||
                (validationJobs + security).filter(String::isNotEmpty).any { "cache-read-only: false" !in workflowStep(it, "Setup Gradle") }
            CiCacheViolation.DEPENDENCY_CHECK_DATA -> !hasExclusiveSecurityCache(workflow, security)
            CiCacheViolation.SECURITY_LIFECYCLE -> !hasCompletedSecurityLifecycle(security)
            CiCacheViolation.HOST_DEPENDENCY_CHECK -> workflowStep(security, "Run Security Check").lines().map(String::trim)
                .filter { it.startsWith("./gradlew") } != listOf("./gradlew verifySecuritySnapshot dependencyCheckSdk -Pmazewall.securitySnapshotScan=true --info --no-configuration-cache --no-daemon --no-parallel --max-workers=1 2>&1 | tee security-analysis.log")
            CiCacheViolation.CONTAINER_DEPENDENCY_CHECK -> "run: ./scripts/run_containerized_tests.sh dependencyCheckAnalyze" in workflow
            CiCacheViolation.IMMUTABLE_NVD_CACHE -> "key: nvd-database-" in workflow
            CiCacheViolation.CONTAINER_CACHE_IDENTITY -> containerJobs.withIndex().any { (index, job) ->
                !hasStableContainerIdentity(job, optional = index == 0)
            }
            CiCacheViolation.CONTAINER_CACHE_SAVE -> containerJobs.withIndex().any { (index, job) ->
                "steps.container_cache_key.outcome == 'success'" !in containerSaveStep(job, optional = index == 0)
            }
            CiCacheViolation.CONTAINER_CACHE_REUSE -> {
                val identity = "${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-"
                containerRestoreStep(containerJobs[1], optional = false).substringAfter("restore-keys: |", "")
                    .lines().map(String::trim).filter(String::isNotEmpty) != listOf(
                    "container-gradle-$identity",
                    "optional-container-gradle-$identity",
                    "optional-container-gradle-${'$'}{{ runner.os }}-",
                    "container-gradle-${'$'}{{ runner.os }}-",
                )
            }
            CiCacheViolation.CORE_COMPILATION_CACHE -> !hasCoreCompilationCache(containerJobs[1])
            CiCacheViolation.SERIAL_VALIDATION -> validationJobs.withIndex().any { (index, job) ->
                val headers = workflowHeaders(job)
                headers.filter { it.startsWith("name:") } != listOf("name: ${validationNames[index]}") ||
                    headers.any { it.startsWith("needs:") || it.startsWith("if:") || it.startsWith("continue-on-error:") }
            }
            CiCacheViolation.VALIDATION_RUNNER -> validationJobs.any { job ->
                val java = workflowStep(job, "Set up JDK 25")
                workflowHeaders(job).filter { it.startsWith("runs-on:") } != listOf("runs-on: ubuntu-26.04") ||
                    "uses: actions/setup-java@v5" !in java || "java-version: \"25\"" !in java
            }
            CiCacheViolation.CODE_CHANGE_FILTER -> validationJobs.drop(1).any { !hasCodeFilter(it) } ||
                workflowStep(security, "Check file changes").trim() != workflowStep(containerJobs[1], "Check file changes").trim()
            CiCacheViolation.MUTATION_EXECUTION -> !hasBoundedMutation(workflow, mutation)
            CiCacheViolation.MUTATION_DIAGNOSTICS -> !hasMutationDiagnostics(mutation)
            CiCacheViolation.OPTIONAL_DIAGNOSTICS -> !hasOptionalDiagnostics(validationJobs[0])
            CiCacheViolation.BUILD_LOGIC_VALIDATION -> !hasBuildLogicValidation(workflow)
            CiCacheViolation.GO_VALIDATION -> !hasGoValidation(workflow)
            CiCacheViolation.REQUIRED_CHECK_JOIN -> !hasRequiredCheckJoin(join)
            CiCacheViolation.PUBLISH_GRAPH -> !hasGatedPublication(workflow, containerJobs[1], join)
            CiCacheViolation.PACKAGE_WRITE_SCOPE -> {
                val outsidePublication = workflow.replace(workflowJobBlock(workflow, "publish"), "")
                val packageWrite = Regex("(?m)^\\s*(?:packages:\\s*write\\b|permissions:.*(?:write-all|packages:\\s*write\\b))")
                packageWrite.containsMatchIn(outsidePublication)
            }
            CiCacheViolation.PR_RUN_CONCURRENCY -> {
                val concurrency = """
                    concurrency:
                        group: ${'$'}{{ github.workflow }}-${'$'}{{ github.event_name }}-${'$'}{{ github.event.pull_request.number || github.run_id }}
                        cancel-in-progress: ${'$'}{{ github.event_name == 'pull_request' }}
                """.trimIndent()
                val headers = workflow.substringBefore("\njobs:")
                concurrency !in headers || headers.lines().count { it == "concurrency:" } != 1
            }
        }
    }
}

private fun hasCoreCompilationCache(core: String): Boolean {
    val resolve = workflowStep(core, "Resolve Core Integration Compilation Cache").trimIndent().trim()
    val restore = workflowStep(core, "Restore Core Integration Compilation Cache").trimIndent().trim()
    val save = workflowStep(core, "Save Core Integration Compilation Cache").trimIndent().trim()
    val ownership = workflowStep(core, "Verify Workspace and Cache Ownership").trimIndent().trim()
    val path = "${'$'}{{ steps.core_compile_cache.outputs.path }}"
    val key = "core-integration-compile-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-${'$'}{{ github.sha }}"
    val guard = "github.event_name == 'pull_request' && steps.core_compile_cache.outcome == 'success' && steps.core_compile_cache.outputs.path != ''"
    return resolve == """
        id: core_compile_cache
        run: |
            cache_home=${'$'}(./scripts/run_containerized_tests.sh --print-cache-home)
            test -n "${'$'}cache_home"
            printf 'path=%s/caches/build-cache-1\n' "${'$'}cache_home" >> "${'$'}GITHUB_OUTPUT"
    """.trimIndent() && ownership.lines().any { it.trim() == "id: core_ownership" } && restore == """
        if: $guard
        uses: actions/cache/restore@v4
        with:
            path: $path
            key: $key
            restore-keys: |
                core-integration-compile-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-
    """.trimIndent() && save == """
        if: always() && github.event_name == 'pull_request' && steps.core_ownership.outcome == 'success' && steps.container_cache_key.outcome == 'success' && steps.core_compile_cache.outcome == 'success' && steps.core_compile_cache.outputs.path != ''
        uses: actions/cache/save@v4
        with:
            path: $path
            key: $key
    """.trimIndent() && core.indexOf("- name: Restore Container Gradle Cache") < core.indexOf("- name: Restore Core Integration Compilation Cache") &&
        core.indexOf("- name: Verify Workspace and Cache Ownership") < core.indexOf("- name: Save Core Integration Compilation Cache")
}

private fun workflowJob(workflow: String, job: String): String =
    workflowJobBlock(workflow, job).substringAfter('\n', "").lines().joinToString("\n") { it.removePrefix("            ") }

private fun workflowJobBlock(workflow: String, job: String): String =
    Regex("(?ms)^    $job:\\n(.*?)(?=^    [a-zA-Z0-9_-]+:|^[^ \\r\\n]|\\z)").find(workflow)
        ?.value.orEmpty()

private fun workflowHeaders(job: String): List<String> = job.substringBefore("        steps:").lines().map(String::trim)

private fun workflowStep(job: String, name: String): String =
    job.substringAfter("- name: $name\n", "").substringBefore("\n- name: ")

private fun hasCodeFilter(job: String): Boolean {
    val prefix = """
        id: filter
        uses: dorny/paths-filter@v3
        with:
            filters: |
                code:
    """.trimIndent() + "\n"
    val filter = workflowStep(job, "Check file changes").trimIndent().trim()
    val paths = filter.substringAfter(prefix, "").lines()
    return filter.startsWith(prefix) && paths.all { Regex(" {12}- '[^']+'").matches(it) } &&
        "            - '.github/workflows/ci.yml'" in paths &&
        workflowHeaders(job).any { it == "pull-requests: read" || it == "pull-requests: write" }
}

private fun hasBoundedMutation(workflow: String, mutation: String): Boolean {
    val execution = """
        if: steps.filter.outputs.code == 'true'
        run: >-
            python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/mutation.json --
            ./gradlew :enforcer:pitest :platform:pitest :profiler:pitest
            --no-daemon --max-workers=1 --no-parallel
            -Pmazewall.pitest.threads=2 -Pmazewall.test.maxParallelForks=1
    """.trimIndent()
    val step = workflowStep(mutation, "Run PITest (Host Unit Tests Only)").trimIndent().trim()
    return step == execution && Regex(":(enforcer|platform|profiler):pitest\\b").findAll(workflow).count() == 3 &&
        listOf("podman", "run_containerized_tests.sh", "container-gradle", "actions/cache/").none { it in mutation } &&
        "uses: actions/checkout@v6" in mutation && "uses: gradle/actions/setup-gradle@v6" in workflowStep(mutation, "Setup Gradle")
}

private fun hasMutationDiagnostics(mutation: String): Boolean {
    val upload = """
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
    """.trimIndent()
    return workflowStep(mutation, "Upload Mutation Diagnostics").trimIndent().trim() == upload
}

private fun hasBuildLogicValidation(workflow: String): Boolean {
    val expected = """
        build-logic-validation:
            name: Build Logic Validation
            runs-on: ubuntu-26.04
            timeout-minutes: 90
            permissions:
                contents: read
            steps:
                - name: Checkout Code
                  uses: actions/checkout@v6
                - name: Set up JDK 25
                  uses: actions/setup-java@v5
                  with:
                      java-version: "25"
                      distribution: "temurin"
                - name: Setup Gradle
                  uses: gradle/actions/setup-gradle@v6
                  with:
                      cache-encryption-key: ${'$'}{{ secrets.GRADLE_ENCRYPTION_KEY }}
                      cache-read-only: false
                - name: Run Build Logic Verification
                  run: |
                      set -o pipefail
                      mkdir -p build/reports/ci-resources
                      python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/build-logic.json -- ./gradlew :buildLogicCheck --configuration-cache --configuration-cache-problems=fail --no-daemon --max-workers=1 --no-parallel 2>&1 | tee build/reports/ci-resources/build-logic.log
                - name: Run Consumer Build Logic Regressions
                  run: ./gradlew :consumerBuildLogicTest --configuration-cache --configuration-cache-problems=fail --no-daemon --max-workers=1 --no-parallel
                - name: Upload Build Logic Diagnostics
                  if: always()
                  uses: actions/upload-artifact@v4
                  with:
                      name: build-logic-diagnostics
                      path: |
                          build/reports/ci-resources/build-logic.json
                          build/reports/ci-resources/build-logic.log
                          build/reports/problems/
                          buildSrc/build/reports/
                          buildSrc/build/test-results/
                          buildSrc/build/verification/
                          build/test-results/consumerBuildLogicTest/
                          build/reports/tests/consumerBuildLogicTest/
                      if-no-files-found: ignore
    """.trimIndent()
    return workflowJobBlock(workflow, "build-logic-validation").trimEnd().trimIndent() == expected
}

private fun hasGoValidation(workflow: String): Boolean {
    val expected = """
        go-validation:
            name: Agentless Go Validation
            if: ${'$'}{{ vars.ENABLE_GO_VALIDATION == 'true' || (github.event_name == 'workflow_dispatch' && inputs.enable_go_validation) }}
            runs-on: ubuntu-26.04
            timeout-minutes: 45
            permissions:
                contents: read
                pull-requests: read
            env:
                GOWORK: "off"
                GOTOOLCHAIN: local
                GOMAXPROCS: "2"
                GOMEMLIMIT: 4GiB
                DOCKER_HOST: unix:///var/run/docker.sock
            steps:
                - name: Checkout Code
                  uses: actions/checkout@v6
                - name: Check Go Changes
                  id: go_changes
                  uses: dorny/paths-filter@v3
                  with:
                      filters: |
                          go:
                              - 'profiler/hotspot-agentless/**'
                              - 'experiments/hotspot-agentless/**'
                              - '.github/workflows/ci.yml'
                              - 'scripts/ci_resource_probe.py'
                - name: Set up Go
                  if: steps.go_changes.outputs.go == 'true' || (github.event_name == 'workflow_dispatch' && inputs.enable_go_validation)
                  uses: actions/setup-go@v6
                  with:
                      go-version-file: profiler/hotspot-agentless/go.mod
                      cache-dependency-path: |
                          profiler/hotspot-agentless/go.sum
                          experiments/hotspot-agentless/go.sum
                - name: Install Pinned Vulnerability Scanner
                  if: steps.go_changes.outputs.go == 'true' || (github.event_name == 'workflow_dispatch' && inputs.enable_go_validation)
                  run: go install golang.org/x/vuln/cmd/govulncheck@v1.9.0
                - name: Run Go Checks
                  if: steps.go_changes.outputs.go == 'true' || (github.event_name == 'workflow_dispatch' && inputs.enable_go_validation)
                  run: |
                      set -o pipefail
                      mkdir -p build/reports/ci-resources
                      python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/go.json -- bash -euo pipefail -c '
                          result=0
                          for module in profiler/hotspot-agentless experiments/hotspot-agentless; do
                              if ! make -C "${'$'}module" unit; then result=1; fi
                              if ! make -C "${'$'}module" vuln; then result=1; fi
                          done
                          exit "${'$'}result"
                      ' 2>&1 | tee build/reports/ci-resources/go.log
                - name: Upload Go Diagnostics
                  if: always()
                  uses: actions/upload-artifact@v4
                  with:
                      name: go-diagnostics
                      path: |
                          build/reports/ci-resources/go.json
                          build/reports/ci-resources/go.log
                      if-no-files-found: ignore
    """.trimIndent()
    return workflowJobBlock(workflow, "go-validation").trimEnd().trimIndent() == expected
}

private fun hasOptionalDiagnostics(job: String): Boolean =
    workflowStep(job, "Upload Optional JUnit Diagnostics").trimIndent().trim() == """
        if: always()
        uses: actions/upload-artifact@v4
        with:
            name: optional-integration-junit
            path: |
                integrations/**/build/test-results/**/*.xml
                build/reports/ci-resources/optional-adapters-cache.log
                build/test-results/optionalIntegrationCheck/*.xml
                build/reports/optional-integrations/run-*/*.log
                build/reports/optional-integrations/run-*/*.command.json
                build/reports/optional-integrations/run-*/*-reports/**
            if-no-files-found: ignore
    """.trimIndent()

private fun hasRequiredCheckJoin(join: String): Boolean {
    val headers = workflowHeaders(join)
    val step = """
        - name: Require Core and Mutation Success
          run: test "${'$'}{{ needs.core-quality.result }}" = success && test "${'$'}{{ needs.mutation-tests.result }}" = success && test "${'$'}{{ needs.security-check.result }}" = success && test "${'$'}{{ needs.build-logic-validation.result }}" = success && (test "${'$'}{{ needs.go-validation.result }}" = success || (test "${'$'}{{ vars.ENABLE_GO_VALIDATION }}" != true && test "${'$'}{{ inputs.enable_go_validation }}" != true && test "${'$'}{{ needs.go-validation.result }}" = skipped)) && test "${'$'}{{ needs.optional-integrations.result }}" = success && test "${'$'}{{ needs.confined-helper-kernel.result }}" = success
    """.trimIndent()
    return headers.filter { it.startsWith("name:") } == listOf("name: Build, Test, Security Scan & Publish") &&
        headers.filter { it.startsWith("needs:") } == listOf("needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation, optional-integrations, confined-helper-kernel]") &&
        headers.filter { it.startsWith("if:") } == listOf("if: always()") &&
        headers.none { it.startsWith("continue-on-error:") } && join.substringAfter("        steps:\n", "").trim() == step
}

private fun hasExclusiveSecurityCache(workflow: String, security: String): Boolean {
    val setup = workflowStep(security, "Setup Gradle")
    val outside = workflow.replace(workflowJobBlock(workflow, "security-check"), "")
    return "gradle-home-cache-excludes: dependency-check-data" in setup &&
        "gradle-home-cache-includes:" !in setup && "dependency-check-data" !in outside.substringAfter("jobs:") &&
        "actions/cache/restore@" !in security && "actions/cache/save@" !in security
}

private fun hasCompletedSecurityLifecycle(security: String): Boolean {
    val controls = workflowStep(security, "Test Security Snapshot Controls")
    val selector = workflowStep(security, "Select Retained Security Snapshot")
    val download = workflowStep(security, "Download Retained Security Snapshot")
    val restore = workflowStep(security, "Restore Retained Security Snapshot")
    val scan = workflowStep(security, "Run Security Check")
    val reports = workflowStep(security, "Require SDK Security Reports")
    return "continue-on-error:" !in security && "dependencyCheckUpdate" !in security && "NVD_API_KEY" !in security &&
        "- '.github/workflows/security-data.yml'" in workflowStep(security, "Check file changes") &&
        controls.trimIndent().trim() == "if: steps.filter.outputs.code == 'true'\nrun: python3 scripts/test_security_snapshot.py" &&
        listOf("getWorkflow", "getWorkflowRunAttempt", "security-data.yml", "ci.yml", "error.status !== 404", "throw error", "scripts/security_snapshot.py", "origin.repository_id !== repositoryId", "origin.head_repository_id !== repositoryId", "origin.head_branch !== defaultBranch", "!trustedWorkflow", "run.head_sha !== origin.head_sha", "Number(match[2]) > run.run_attempt").all { it in selector } &&
        listOf("archiveSize > 2147483648", "bytes > archiveSize", "bytes !== archiveSize", "parseSuccessResponseBody: false", "flags: 'wx'").all { it in download } &&
        listOf("SNAPSHOT_KEY", "SNAPSHOT_DIGEST", "scripts/security_snapshot.py restore").all { it in restore } &&
        scan.lines().map(String::trim).firstOrNull { it.startsWith("if:") } == "if: steps.filter.outputs.code == 'true'" && "verifySecuritySnapshot dependencyCheckSdk" in scan &&
        reports.lines().map(String::trim).firstOrNull { it.startsWith("if:") } == "if: steps.filter.outputs.code == 'true'" &&
        listOf("build/reports/dependency-check-report.json", "platform/build/reports/dependency-check-report.json",
            "enforcer/build/reports/dependency-check-report.json", "profiler/build/reports/dependency-check-report.json",
            "portals/runtime/build/reports/dependency-check-report.json", "portals/worker/build/reports/dependency-check-report.json",
            "portals/codegen/build/reports/dependency-check-report.json", "set -e", "test -s \"${'$'}report\"").all { it in reports } &&
        "-Pmazewall.securitySnapshotScan=true" in scan && "--no-configuration-cache" in scan &&
        security.indexOf("- name: Select Retained Security Snapshot") < security.indexOf("- name: Download Retained Security Snapshot") &&
        security.indexOf("- name: Download Retained Security Snapshot") < security.indexOf("- name: Restore Retained Security Snapshot") &&
        security.indexOf("- name: Restore Retained Security Snapshot") < security.indexOf("- name: Verify Restored Security Ownership") &&
        security.indexOf("- name: Verify Restored Security Ownership") < security.indexOf("- name: Run Security Check")
}

internal fun securityUpdaterPolicyViolations(workflow: String): List<String> {
    val failures = mutableListOf<String>()
    fun require(condition: Boolean, reason: String) { if (!condition) failures.add(reason) }
    require("schedule:" in workflow && "workflow_dispatch:" in workflow && "pull_request:" !in workflow, "events")
    require("github.ref == format('refs/heads/{0}', github.event.repository.default_branch)" in workflow, "default branch")
    require("group: security-data-updater" in workflow && "cancel-in-progress: false" in workflow, "concurrency")
    val job = workflowJob(workflow, "update")
    val update = workflowStep(job, "Update Security Data")
    require("-Pmazewall.securitySnapshotUpdate=true" in update && "dependencyCheckUpdate" in update && "set -o pipefail" in update && "check-records security-update.log" in update, "forced update and records")
    require("verifySecuritySnapshot" in workflowStep(job, "Verify Security Snapshot"), "database verification")
    val guards = listOf("!cancelled()", "steps.security_update.outcome == 'success'", "steps.updated_ownership.outcome == 'success'", "steps.verified_snapshot.outcome == 'success'")
    for (name in listOf("Prepare Completed Security Artifact", "Upload Completed Security Artifact", "Require Retained Security Snapshot", "Save Completed Security Data", "Look Up Completed Security Snapshot", "Require Completed Security Snapshot")) {
        val step = workflowStep(job, name)
        val condition = step.lines().map(String::trim).firstOrNull { it.startsWith("if:") }.orEmpty()
        val expected = guards.joinToString(" && ") + if (name == "Upload Completed Security Artifact") " && steps.security_artifact_prepare.outcome == 'success'" else ""
        require(condition == "if: ${'$'}{{ $expected }}", name)
    }
    require("steps.security_artifact_prepare.outcome == 'success'" in workflowStep(job, "Upload Completed Security Artifact"), "artifact preparation")
    require("lookup-only: true" in workflowStep(job, "Look Up Completed Security Snapshot") && "fail-on-cache-miss: true" in workflowStep(job, "Look Up Completed Security Snapshot"), "cache lookup")
    require("test \"${'$'}{CACHE_HIT:-}\" = true" in workflowStep(job, "Require Completed Security Snapshot"), "cache proof")
    return failures
}

private fun hasGatedPublication(workflow: String, main: String, join: String): Boolean {
    val publish = workflowJob(workflow, "publish")
    val headers = publish.substringBefore("        steps:").lines().map(String::trim)
    val command = "publishAllPublicationsToGitHubPackagesRepository"
    val defaultPush = "if: github.event_name == 'push' && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master')"
    val restore = publish.substringAfter("- name: Restore Container Gradle Cache\n", "").substringBefore("\n- name: ")
    val key = "key: container-gradle-${'$'}{{ runner.os }}-${'$'}{{ needs.build-test-publish.outputs.container_cache_hash }}-${'$'}{{ github.sha }}"
    return "needs: [optional-integrations, build-test-publish]" in headers && defaultPush in headers &&
        headers.count { it.startsWith("needs:") } == 1 && headers.count { it.startsWith("if:") } == 1 &&
        command in publish && Regex(command).findAll(workflow).count() == 1 &&
        "container_cache_hash: ${'$'}{{ steps.container_cache_key.outputs.hash }}" in main &&
        "container_cache_hash: ${'$'}{{ needs.core-quality.outputs.container_cache_hash }}" in join &&
        restore.lines().map(String::trim).filter { it.startsWith("key: ") } == listOf(key)
}

private fun containerSaveStep(job: String, optional: Boolean): String {
    val name = if (optional) "Save Optional Container Gradle Cache" else "Save Container Gradle Cache (Master Only)"
    return job.substringAfter("- name: $name\n", "").substringBefore("\n- name: ")
}

private fun containerRestoreStep(job: String, optional: Boolean): String {
    val name = if (optional) "Restore Optional Container Gradle Cache" else "Restore Container Gradle Cache"
    return job.substringAfter("- name: $name\n", "").substringBefore("\n- name: ")
}

private fun hasStableContainerIdentity(job: String, optional: Boolean): Boolean {
    val producer = """
        - name: Checkout Code
          uses: actions/checkout@v6
        - name: Compute Container Gradle Cache Key
          id: container_cache_key
          run: |
              hash=${'$'}(./scripts/ci_cache_key.sh)
              echo "hash=${'$'}hash" >> "${'$'}GITHUB_OUTPUT"
    """.trimIndent()
    val prefix = if (optional) "optional-container-gradle" else "container-gradle"
    val identity = "$prefix-${'$'}{{ runner.os }}-${'$'}{{ steps.container_cache_key.outputs.hash }}-"
    val restore = containerRestoreStep(job, optional)
    val save = containerSaveStep(job, optional)
    return producer in job && job.lines().count { it.trim() == "id: container_cache_key" } == 1 &&
        listOf(restore, save).all { step ->
            step.lines().map(String::trim).filter { it.startsWith("key: ") } == listOf("key: $identity${'$'}{{ github.sha }}")
        } && identity in restore.lines().map(String::trim)
}

/** Public task type is instantiated by Gradle scripts outside the buildSrc Kotlin module. */
@DisableCachingByDefault(because = "A read-only verification with no generated output")
abstract class CheckCiCachePolicy : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workflow: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val securityUpdater: java.io.File get() = workflow.get().asFile.resolveSibling("security-data.yml")

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val securitySnapshotHelper: java.io.File
        get() = workflow.get().asFile.parentFile.parentFile.parentFile.resolve("scripts/security_snapshot.py")

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val securitySnapshotTests: java.io.File
        get() = workflow.get().asFile.parentFile.parentFile.parentFile.resolve("scripts/test_security_snapshot.py")

    @TaskAction
    fun verify() {
        val updaterFailures = securityUpdaterPolicyViolations(securityUpdater.readText())
        if (updaterFailures.isNotEmpty()) throw GradleException("SECURITY_UPDATER: " + updaterFailures.joinToString())
        val violations = ciCachePolicyViolations(workflow.get().asFile.readText())
        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n") { "${it.name}: ${it.explanation}" })
        }
    }
}
