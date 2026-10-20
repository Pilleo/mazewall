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
    DEPENDENCY_CHECK_DATA("setup-gradle must include OWASP Dependency-Check data"),
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
    REQUIRED_CHECK_JOIN("The unchanged required check must always evaluate and require core and mutation success"),
    PUBLISH_GRAPH("Publication must run only after both validations succeed on a default-branch push"),
    PACKAGE_WRITE_SCOPE("Only the publication job may have package-write permission"),
    PR_RUN_CONCURRENCY("Only superseded PR runs may cancel; push runs need separate run-ID groups"),
}

internal fun ciCachePolicyViolations(workflow: String): List<CiCacheViolation> {
    val validationNames = listOf("Koog and Spring Integration Acceptance", "Core Quality", "Mutation Tests")
    val validationJobs = listOf("optional-integrations", "core-quality", "mutation-tests").map { workflowJob(workflow, it) }
    val containerJobs = validationJobs.take(2)
    val mutation = validationJobs[2]
    val join = workflowJob(workflow, "build-test-publish")
    return CiCacheViolation.entries.filter { violation ->
        when (violation) {
            CiCacheViolation.PR_CACHE_WRITES -> "cache-read-only: false" !in workflow ||
                validationJobs.filter(String::isNotEmpty).any { "cache-read-only: false" !in workflowStep(it, "Setup Gradle") }
            CiCacheViolation.DEPENDENCY_CHECK_DATA -> "dependency-check-data" !in workflow
            CiCacheViolation.HOST_DEPENDENCY_CHECK -> "run: ./gradlew dependencyCheckAnalyze --info --no-configuration-cache --no-daemon" !in workflow
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
            CiCacheViolation.CODE_CHANGE_FILTER -> validationJobs.drop(1).any { !hasCodeFilter(it) }
            CiCacheViolation.MUTATION_EXECUTION -> !hasBoundedMutation(workflow, mutation)
            CiCacheViolation.MUTATION_DIAGNOSTICS -> !hasMutationDiagnostics(mutation)
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

private fun hasRequiredCheckJoin(join: String): Boolean {
    val headers = workflowHeaders(join)
    val step = """
        - name: Require Core and Mutation Success
          run: test "${'$'}{{ needs.core-quality.result }}" = success && test "${'$'}{{ needs.mutation-tests.result }}" = success
    """.trimIndent()
    return headers.filter { it.startsWith("name:") } == listOf("name: Build, Test, Security Scan & Publish") &&
        headers.filter { it.startsWith("needs:") } == listOf("needs: [core-quality, mutation-tests]") &&
        headers.filter { it.startsWith("if:") } == listOf("if: always()") &&
        headers.none { it.startsWith("continue-on-error:") } && join.substringAfter("        steps:\n", "").trim() == step
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
