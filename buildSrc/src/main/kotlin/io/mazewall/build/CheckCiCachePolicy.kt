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
}

internal fun ciCachePolicyViolations(workflow: String): List<CiCacheViolation> {
    val containerJobs = listOf("optional-integrations", "build-test-publish").map { job ->
        Regex("(?ms)^    $job:\\n(.*?)(?=^    [a-zA-Z0-9_-]+:|^[^ \\r\\n]|\\z)").find(workflow)
            ?.groupValues?.get(1).orEmpty().lines().joinToString("\n") { it.removePrefix("            ") }
    }
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
        }
    }
}

private fun containerSaveStep(job: String, optional: Boolean): String {
    val name = if (optional) "Save Optional Container Gradle Cache" else "Save Container Gradle Cache (Master Only)"
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
    val restoreName = if (optional) "Restore Optional Container Gradle Cache" else "Restore Container Gradle Cache"
    val restore = job.substringAfter("- name: $restoreName\n", "").substringBefore("\n- name: ")
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
