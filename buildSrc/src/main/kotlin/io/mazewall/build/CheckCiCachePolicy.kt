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
    SERIAL_VALIDATION("Both required validation jobs must run independently with unchanged check names"),
    PUBLISH_GRAPH("Publication must run only after both validations succeed on a default-branch push"),
    PR_RUN_CONCURRENCY("Only superseded PR runs may cancel; push runs need separate run-ID groups"),
}

internal fun ciCachePolicyViolations(workflow: String): List<CiCacheViolation> {
    val containerJobs = listOf("optional-integrations", "build-test-publish").map { workflowJob(workflow, it) }
    return CiCacheViolation.entries.filter { violation ->
        when (violation) {
            CiCacheViolation.PR_CACHE_WRITES -> "cache-read-only: false" !in workflow
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
            CiCacheViolation.SERIAL_VALIDATION -> containerJobs.withIndex().any { (index, job) ->
                val headers = job.substringBefore("        steps:").lines().map(String::trim)
                val name = if (index == 0) "Koog and Spring Integration Acceptance" else "Build, Test, Security Scan & Publish"
                "name: $name" !in headers || headers.any { it.startsWith("needs:") || it.startsWith("if:") }
            }
            CiCacheViolation.PUBLISH_GRAPH -> !hasGatedPublication(workflow, containerJobs[1])
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
    Regex("(?ms)^    $job:\\n(.*?)(?=^    [a-zA-Z0-9_-]+:|^[^ \\r\\n]|\\z)").find(workflow)
        ?.groupValues?.get(1).orEmpty().lines().joinToString("\n") { it.removePrefix("            ") }

private fun hasGatedPublication(workflow: String, main: String): Boolean {
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
