package io.mazewall.orchestrator

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

    fun normalize(path: String): String =
        path.replace('\\', '/').removePrefix("./").trimStart('/')

    fun moduleFor(path: String): String? {
        val n = normalize(path)
        return when {
            n.startsWith("enforcer/") || n == "enforcer/AGENTS.md" -> ":enforcer"
            n.startsWith("profiler/") || n == "profiler/AGENTS.md" -> ":profiler"
            n.startsWith("platform/") || n == "platform/AGENTS.md" -> ":platform"
            n.startsWith("portal-codegen/") -> ":portal-codegen"
            n.startsWith("portal-worker/") -> ":portal-worker"
            n.startsWith("portal/") -> ":portal"
            n.startsWith("tools/orchestrator/") -> ":tools:orchestrator"
            n.startsWith("demos/cli-demo/") -> ":demos:cli-demo"
            n.startsWith("demos/vulnerable-web-app/") -> ":demos:vulnerable-web-app"
            n.startsWith("demos/agent-sandbox-demo/") -> ":demos:agent-sandbox-demo"
            else -> null
        }
    }

    fun isCoreLock(path: String): Boolean {
        val n = normalize(path)
        if (n == "AGENTS.md" || n == "build.gradle.kts" || n == "settings.gradle.kts") {
            return true
        }
        return CORE_LOCK_SUFFIXES.any { n.endsWith(it) }
    }

    fun componentFor(module: String): String = when (module) {
        ":enforcer" -> "enforcer"
        ":profiler" -> "profiler"
        ":platform" -> "platform"
        ":tools:orchestrator" -> "orchestrator"
        ":portal", ":portal-codegen", ":portal-worker" -> "docs"
        else -> "testing"
    }

    fun verifyCheapCommand(testFile: String): String? {
        val n = normalize(testFile)
        if (!n.endsWith("Test.kt") && !n.endsWith("Test.java")) return null
        val module = moduleFor(n) ?: return null
        val marker = "/kotlin/"
        val idx = n.indexOf(marker)
        if (idx < 0) return null
        val fqcn = n.substring(idx + marker.length)
            .removeSuffix(".kt")
            .removeSuffix(".java")
            .replace('/', '.')
        return "./gradlew $module:test --tests $fqcn"
    }
}
