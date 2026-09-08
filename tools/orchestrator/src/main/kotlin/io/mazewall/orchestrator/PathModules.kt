package io.mazewall.orchestrator

internal enum class GradleProjectIdentity(
    val pathPrefix: String,
    val gradlePath: String,
    val component: String,
) {
    Enforcer("enforcer/", ":enforcer", "enforcer"),
    Profiler("profiler/", ":profiler", "profiler"),
    Platform("platform/", ":platform", "platform"),
    PortalCodegen("portal-codegen/", ":portal-codegen", "docs"),
    PortalWorker("portal-worker/", ":portal-worker", "docs"),
    Portal("portal/", ":portal", "docs"),
    Orchestrator("tools/orchestrator/", ":tools:orchestrator", "orchestrator"),
    CliDemo("demos/cli-demo/", ":demos:cli-demo", "testing"),
    VulnerableWebDemo("demos/vulnerable-web-app/", ":demos:vulnerable-web-app", "testing"),
    AgentSandboxDemo("demos/agent-sandbox-demo/", ":demos:agent-sandbox-demo", "testing"),
    ;

    companion object {
        fun fromPath(path: String): GradleProjectIdentity? =
            entries.firstOrNull { path.startsWith(it.pathPrefix) }

        fun fromGradlePath(gradlePath: String): GradleProjectIdentity? =
            entries.firstOrNull { it.gradlePath == gradlePath }
    }
}

object PathModules {
    /** Single source of truth for work-package `core_lock_hit` and scheduler CORE locks. */
    val CORE_LOCK_SUFFIXES = listOf(
        "/Syscall.kt",
        "/Arch.kt",
        "/Policy.kt",
        "/Platform.kt",
        "/ArchitectureTest.kt",
        "/AGENTS.md",
        "/build.gradle.kts",
        "/settings.gradle.kts",
    )

    fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./").trimStart('/')

    internal fun identityFor(path: String): GradleProjectIdentity? = GradleProjectIdentity.fromPath(normalize(path))

    fun moduleFor(path: String): String? = identityFor(path)?.gradlePath

    fun isCoreLock(path: String): Boolean {
        val n = normalize(path)
        if (n == "AGENTS.md" || n == "build.gradle.kts" || n == "settings.gradle.kts") {
            return true
        }
        return CORE_LOCK_SUFFIXES.any { n.endsWith(it) }
    }

    fun componentFor(module: String): String =
        requireNotNull(GradleProjectIdentity.fromGradlePath(module)) {
            "unknown Gradle module '$module'; add it to GradleProjectIdentity"
        }.component

    fun verifyCheapCommand(testFile: String): String? {
        val n = normalize(testFile)
        if (!n.endsWith("Test.kt") && !n.endsWith("Test.java")) return null
        val module = moduleFor(n) ?: return null
        val marker = "/kotlin/"
        val idx = n.indexOf(marker)
        if (idx < 0) return null
        val fqcn = n
            .substring(idx + marker.length)
            .removeSuffix(".kt")
            .removeSuffix(".java")
            .replace('/', '.')
        return "./gradlew $module:test --tests $fqcn"
    }
}
