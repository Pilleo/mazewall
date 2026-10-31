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
    SERIAL_VALIDATION("Optional, core and mutation validation must run independently without allowing failure"),
    VALIDATION_RUNNER("All three validation lanes must use ubuntu-26.04 and JDK 25"),
    CODE_CHANGE_FILTER("Core and mutation need unconditional PR-readable code filters including the CI workflow"),
    MUTATION_EXECUTION("Run all three PIT tasks once, serially through the host resource probe with bounded workers and forks"),
    MUTATION_DIAGNOSTICS("Always upload the mutation resource receipt and all three PIT reports"),
    OPTIONAL_DIAGNOSTICS("Always retain optional command logs and copied-consumer test reports, including failures"),
    BUILD_LOGIC_VALIDATION("Independent read-only build logic must run with strict configuration cache, bounded resources and always-uploaded diagnostics"),
    GO_VALIDATION("Path-gated Go tests and pinned vulnerability scanning must be required, bounded and fail closed for both modules"),
    REQUIRED_CHECK_JOIN("The unchanged required check must always evaluate and require core, mutation, security, build-logic, Go and optional-integration success"),
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
                .filter { it.startsWith("./gradlew") } != listOf("./gradlew dependencyCheckAnalyze --info --no-configuration-cache --no-daemon -Dnvd.api.results.per.page=500 2>&1 | tee security-analysis.log")
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
                      python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/build-logic.json -- ./gradlew buildLogicCheck --configuration-cache --configuration-cache-problems=fail --no-daemon --max-workers=1 --no-parallel 2>&1 | tee build/reports/ci-resources/build-logic.log
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
                      if-no-files-found: ignore
    """.trimIndent()
    return workflowJobBlock(workflow, "build-logic-validation").trimEnd().trimIndent() == expected
}

private fun hasGoValidation(workflow: String): Boolean {
    val expected = """
        go-validation:
            name: Agentless Go Validation
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
                  if: steps.go_changes.outputs.go == 'true'
                  uses: actions/setup-go@v6
                  with:
                      go-version-file: profiler/hotspot-agentless/go.mod
                      cache-dependency-path: |
                          profiler/hotspot-agentless/go.sum
                          experiments/hotspot-agentless/go.sum
                - name: Install Pinned Vulnerability Scanner
                  if: steps.go_changes.outputs.go == 'true'
                  run: go install golang.org/x/vuln/cmd/govulncheck@v1.9.0
                - name: Run Go Checks
                  if: steps.go_changes.outputs.go == 'true'
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
          run: test "${'$'}{{ needs.core-quality.result }}" = success && test "${'$'}{{ needs.mutation-tests.result }}" = success && test "${'$'}{{ needs.security-check.result }}" = success && test "${'$'}{{ needs.build-logic-validation.result }}" = success && test "${'$'}{{ needs.go-validation.result }}" = success && test "${'$'}{{ needs.optional-integrations.result }}" = success
    """.trimIndent()
    return headers.filter { it.startsWith("name:") } == listOf("name: Build, Test, Security Scan & Publish") &&
        headers.filter { it.startsWith("needs:") } == listOf("needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation, optional-integrations]") &&
        headers.filter { it.startsWith("if:") } == listOf("if: always()") &&
        headers.none { it.startsWith("continue-on-error:") } && join.substringAfter("        steps:\n", "").trim() == step
}

private fun hasExclusiveSecurityCache(workflow: String, security: String): Boolean {
    val setup = workflowStep(security, "Setup Gradle")
    val restore = workflowStep(security, "Restore Security Data").trimIndent().trim()
    val save = workflowStep(security, "Save Completed Security Data").trimIndent().trim()
    val data = "${'$'}{{ steps.security_metadata.outputs.data_dir }}"
    val key = "${'$'}{{ steps.security_metadata.outputs.key }}"
    val prefix = "${'$'}{{ steps.security_metadata.outputs.prefix }}"
    val outside = workflow.replace(workflowJobBlock(workflow, "security-check"), "")
    return "gradle-home-cache-excludes: dependency-check-data" in setup &&
        "gradle-home-cache-includes:" !in setup && "dependency-check-data" !in outside.substringAfter("jobs:") &&
        restore.substringAfter("uses: ").trim() == """
            actions/cache/restore@v4
            with:
                path: $data
                key: $key
                restore-keys: $prefix
        """.trimIndent() && save.substringAfter("uses: ").trim() == """
            actions/cache/save@v4
            with:
                path: $data
                key: ${'$'}{{ steps.security_restore.outputs.cache-primary-key }}
        """.trimIndent() && Regex("uses: actions/cache/").findAll(security).count() == 3
}

private fun hasCompletedSecurityLifecycle(security: String): Boolean {
    if ("continue-on-error:" in security) return false
    val eligible = "steps.filter.outputs.code == 'true' && steps.security_metadata.outputs.eligible == 'true'"
    val completed = "${'$'}{{ !cancelled() && steps.security_update.outcome == 'success' && steps.updated_ownership.outcome == 'success' && $eligible }}"
    val binding = "NVD_API_KEY: ${'$'}{{ secrets.NVD_API_KEY }}"
    val headers = workflowHeaders(security).filter(String::isNotBlank)
    if (headers != listOf(
            "name: Security Validation", "runs-on: ubuntu-26.04", "timeout-minutes: 180",
            "permissions:", "contents: read", "pull-requests: read", "actions: read",
        ) || !hasCodeFilter(security) ||
        workflowStep(security, "Checkout Code").trim() != "uses: actions/checkout@v6" ||
        workflowStep(security, "Set up JDK 25").trimIndent().trim() != """
            uses: actions/setup-java@v5
            with:
                java-version: "25"
                distribution: "temurin"
        """.trimIndent() ||
        workflowStep(security, "Setup Gradle").trimIndent().trim().replace("cache-read-only: true", "cache-read-only: false") != """
            uses: gradle/actions/setup-gradle@v6
            with:
                cache-encryption-key: ${'$'}{{ secrets.GRADLE_ENCRYPTION_KEY }}
                cache-read-only: false
                gradle-home-cache-excludes: dependency-check-data
        """.trimIndent()
    ) {
        return false
    }
    val expectedNames = listOf(
        "Checkout Code", "Set up JDK 25", "Check file changes", "Setup Gradle", "Compute Security Data Metadata",
        "Restore Security Data", "Prepare Security Snapshot Tools", "Select Retained Security Snapshot",
        "Download Retained Security Snapshot", "Restore Retained Security Snapshot",
        "Verify Restored Security Ownership", "Update Security Data", "Verify Updated Security Ownership",
        "Prepare Completed Security Artifact", "Upload Completed Security Artifact", "Require Retained Security Snapshot",
        "Save Completed Security Data", "Look Up Completed Security Snapshot", "Require Completed Security Snapshot",
        "Run Security Check", "Verify Final Security Ownership", "Upload Security Diagnostics",
    )
    if (security
        .lines()
        .map(String::trim)
        .filter { it.startsWith("- name: ") }
        .map { it.removePrefix("- name: ") } != expectedNames
    ) {
            return false
        }
    val metadata = """
        id: security_metadata
        env:
            $binding
        run: |
            python3 - <<'PY'
            import os
            import re
            import tomllib
            import unicodedata
            from pathlib import Path

            with open('gradle/libs.versions.toml', 'rb') as catalog:
                version = tomllib.load(catalog)['versions']['dependencyCheck']
            if not isinstance(version, str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)+(?:-[A-Za-z0-9.]+)?', version):
                raise SystemExit('Unsafe Dependency-Check version')
            home = os.environ['GRADLE_USER_HOME']
            if not Path(home).is_absolute() or any(char in home for char in '\r\n'):
                raise SystemExit('Expected absolute GRADLE_USER_HOME')
            prefix = f"dc-data-v1-{os.environ['RUNNER_OS']}-{os.environ['RUNNER_ARCH']}-dc{version}_"
            key = f"{prefix}{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}"
            value = os.environ.get('NVD_API_KEY', '')
            blank = all(unicodedata.category(char) in ('Zs', 'Zl', 'Zp') or char in '\t\n\v\f\r\x1c\x1d\x1e\x1f' for char in value)
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                print(f'data_dir={Path(home) / "dependency-check-data"}', file=output)
                print(f'prefix={prefix}', file=output)
                print(f'key={key}', file=output)
                print(f'eligible={str(not blank).lower()}', file=output)
            PY
    """.trimIndent()
    val ownership = """
        run: |
            for path in "${'$'}GITHUB_WORKSPACE" "${'$'}{GRADLE_USER_HOME:?}" "${'$'}{GRADLE_USER_HOME:?}/dependency-check-data"; do
                if [ -e "${'$'}path" ]; then
                    foreign=${'$'}(find "${'$'}path" -xdev ! -user "${'$'}(id -u)" -print -quit)
                    if [ -n "${'$'}foreign" ]; then
                        echo "Unexpected non-caller-owned security output: ${'$'}foreign" >&2
                        exit 1
                    fi
                fi
            done
    """.trimIndent()
    fun matches(name: String, expected: String): Boolean = workflowStep(security, name).trimIndent().trim() == expected
    val restoreHeader = workflowStep(security, "Restore Security Data").substringBefore("uses: ").trimIndent().trim()
    val saveHeader = workflowStep(security, "Save Completed Security Data").substringBefore("uses: ").trim()
    val analyze = workflowStep(security, "Run Security Check").trimIndent().trim()
    return hasRetainedSecurityLifecycle(security, eligible, completed) && matches("Compute Security Data Metadata", metadata) &&
        restoreHeader == "id: security_restore\nif: $eligible" && saveHeader == "if: $completed" &&
        matches("Verify Restored Security Ownership", "id: restored_ownership\nif: $eligible\n$ownership") &&
        matches("Update Security Data", """
            id: security_update
            if: $eligible && steps.restored_ownership.outcome == 'success'
            timeout-minutes: 150
            env:
                $binding
            run: |
                set -o pipefail
                : > security-update.log
                api_options=(-Dnvd.api.results.per.page=500)
                if [[ ! -s "${'$'}GRADLE_USER_HOME/dependency-check-data/odc.mv.db" ]]; then
                    ./gradlew dependencyCheckUpdate --info --no-configuration-cache --no-daemon '-Dnvd.api.datafeed.url=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz' 2>&1 | tee -a security-update.log
                    python3 "${'$'}RUNNER_TEMP/security-snapshot.py" check-records security-update.log
                    api_options+=(-Dnvd.api.check.validforhours=0)
                fi
                ./gradlew dependencyCheckUpdate --info --no-configuration-cache --no-daemon "${'$'}{api_options[@]}" 2>&1 | tee -a security-update.log
                python3 "${'$'}RUNNER_TEMP/security-snapshot.py" check-records security-update.log
        """.trimIndent()) &&
        matches("Verify Updated Security Ownership", "id: updated_ownership\nif: $eligible && steps.security_update.outcome == 'success'\n$ownership") &&
        matches("Look Up Completed Security Snapshot", """
            id: security_snapshot_lookup
            if: $completed
            uses: actions/cache/restore@v4
            with:
                path: ${'$'}{{ steps.security_metadata.outputs.data_dir }}
                key: ${'$'}{{ steps.security_restore.outputs.cache-primary-key }}
                lookup-only: true
                fail-on-cache-miss: true
        """.trimIndent()) &&
        matches("Require Completed Security Snapshot", """
            if: $completed
            env:
                CACHE_HIT: ${'$'}{{ steps.security_snapshot_lookup.outputs.cache-hit }}
            run: test "${'$'}{CACHE_HIT:-}" = true
        """.trimIndent()) &&
        analyze == """
            if: $completed
            run: |
                set -o pipefail
                ./gradlew dependencyCheckAnalyze --info --no-configuration-cache --no-daemon -Dnvd.api.results.per.page=500 2>&1 | tee security-analysis.log
                python3 "${'$'}RUNNER_TEMP/security-snapshot.py" check-records security-analysis.log
            env:
                $binding
        """.trimIndent() &&
        matches("Verify Final Security Ownership", "if: always()\n$ownership") &&
        matches("Upload Security Diagnostics", """
            if: always()
            uses: actions/upload-artifact@v4
            with:
                name: security-diagnostics
                path: |
                    security-update.log
                    security-analysis.log
                    build/reports/dependency-verification/
                    **/build/reports/dependency-check*
                if-no-files-found: ignore
        """.trimIndent())
}

private fun hasRetainedSecurityLifecycle(security: String, eligible: String, completed: String): Boolean {
    fun matches(name: String, expected: String): Boolean = workflowStep(security, name).trimIndent().trim() == expected.trimIndent()
    val tools = workflowStep(security, "Prepare Security Snapshot Tools").trimIndent().trim()
    val selector = workflowStep(security, "Select Retained Security Snapshot")
    val download = workflowStep(security, "Download Retained Security Snapshot")
    return tools.startsWith("if: $eligible\nrun: |\n    cat > \"\$RUNNER_TEMP/security-snapshot.py\" <<'PY'\n") &&
        selector.substringBefore("script: |").trimIndent().trim() == """
            id: security_artifact
            if: $eligible && steps.security_restore.outputs.cache-matched-key == ''
            uses: actions/github-script@v8
            env:
                SNAPSHOT_PREFIX: ${'$'}{{ steps.security_metadata.outputs.prefix }}
            with:
        """.trimIndent() &&
        download.substringBefore("script: |").trimIndent().trim() == """
            id: security_artifact_download
            if: $eligible && steps.security_artifact.outputs.artifact_id != ''
            uses: actions/github-script@v8
            env:
                SNAPSHOT_ARTIFACT_ID: ${'$'}{{ steps.security_artifact.outputs.artifact_id }}
                SNAPSHOT_SIZE: ${'$'}{{ steps.security_artifact.outputs.size }}
            with:
        """.trimIndent() && "request: {parseSuccessResponseBody: false}" in download &&
        "archiveSize > 2147483648" in download && "bytes > archiveSize" in download && "bytes !== archiveSize" in download &&
        "{flags: 'wx', mode: 0o600}" in download &&
        matches("Restore Retained Security Snapshot", """
            if: $eligible && steps.security_artifact_download.outcome == 'success'
            env:
                SNAPSHOT_KEY: ${'$'}{{ steps.security_artifact.outputs.key }}
                SNAPSHOT_DIGEST: ${'$'}{{ steps.security_artifact.outputs.digest }}
            run: python3 "${'$'}RUNNER_TEMP/security-snapshot.py" restore "${'$'}RUNNER_TEMP/security-snapshot.zip" "${'$'}GRADLE_USER_HOME/dependency-check-data"
        """) &&
        matches("Prepare Completed Security Artifact", """
            id: security_artifact_prepare
            if: $completed
            env:
                SNAPSHOT_KEY: ${'$'}{{ steps.security_metadata.outputs.key }}
            run: python3 "${'$'}RUNNER_TEMP/security-snapshot.py" pack "${'$'}GRADLE_USER_HOME/dependency-check-data" "${'$'}RUNNER_TEMP/security-snapshot-upload"
        """) &&
        matches("Upload Completed Security Artifact", """
            id: security_artifact_upload
            if: ${completed.removeSuffix(" }}")} && steps.security_artifact_prepare.outcome == 'success' }}
            uses: actions/upload-artifact@v4
            with:
                name: security-snapshot-${'$'}{{ steps.security_metadata.outputs.key }}
                path: ${'$'}{{ runner.temp }}/security-snapshot-upload
                retention-days: 14
                include-hidden-files: true
                if-no-files-found: error
        """) &&
        matches("Require Retained Security Snapshot", """
            if: $completed
            env:
                ARTIFACT_ID: ${'$'}{{ steps.security_artifact_upload.outputs.artifact-id }}
            run: |
                [[ "${'$'}{ARTIFACT_ID:-}" =~ ^[1-9][0-9]*$ ]]
        """)
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

    @TaskAction
    fun verify() {
        val violations = ciCachePolicyViolations(workflow.get().asFile.readText())
        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n") { "${it.name}: ${it.explanation}" })
        }
    }
}
