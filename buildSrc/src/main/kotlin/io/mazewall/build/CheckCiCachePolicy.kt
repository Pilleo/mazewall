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
}

internal fun ciCachePolicyViolations(workflow: String): List<CiCacheViolation> =
    CiCacheViolation.entries.filter { violation ->
        when (violation) {
            CiCacheViolation.PR_CACHE_WRITES -> "cache-read-only: false" !in workflow
            CiCacheViolation.DEPENDENCY_CHECK_DATA -> "dependency-check-data" !in workflow
            CiCacheViolation.HOST_DEPENDENCY_CHECK -> "run: ./gradlew dependencyCheckAnalyze --info --no-configuration-cache --no-daemon" !in workflow
            CiCacheViolation.CONTAINER_DEPENDENCY_CHECK -> "run: ./scripts/run_containerized_tests.sh dependencyCheckAnalyze" in workflow
            CiCacheViolation.IMMUTABLE_NVD_CACHE -> "key: nvd-database-" in workflow
        }
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
