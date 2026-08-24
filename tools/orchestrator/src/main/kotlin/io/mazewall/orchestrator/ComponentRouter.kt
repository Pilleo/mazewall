package io.mazewall.orchestrator

/**
 * plan.md Phase-4 routing: backlog `component:` frontmatter (surfaced by the ingest
 * script as "**Component:** …" in the board description) selects the executing agent.
 * Unknown/absent components fall back to the default worker adapter (Jules).
 */
class ComponentRouter(
    val defaultAdapter: String = DEFAULT_ADAPTER,
    customRoutes: Map<String, String> = emptyMap(),
) {
    private val routes: Map<String, String> = DEFAULT_ROUTES + customRoutes

    /** Resolves the roster urlKey for a component, or null to use the default adapter. */
    fun urlKeyFor(component: String?): String? = component?.trim()?.lowercase()?.let { routes[it] }

    /** Extracts the component from an ingested issue description, if present. */
    fun componentOf(description: String?): String? =
        description?.let { COMPONENT_RE.find(it)?.groupValues?.get(1) }

    companion object {
        const val DEFAULT_ADAPTER = "jules"

        val DEFAULT_ROUTES: Map<String, String> = mapOf(
            "enforcer" to "antigravity-acp-developer",
            "kernel" to "antigravity-acp-developer",
            "seccomp" to "antigravity-acp-developer",
            "landlock" to "antigravity-acp-developer",
            "ffm" to "antigravity-acp-developer",
            "portal" to "antigravity-acp-developer",
            "profiler" to "implementation-software-developer-grok",
            "shell" to "implementation-software-developer-grok",
            "podman" to "implementation-software-developer-grok",
            "container" to "implementation-software-developer-grok",
            "platform" to "implementation-software-developer-grok",
            "docs" to "founding-systems-security-engineer",
            "architecture" to "founding-systems-security-engineer",
            "spec" to "founding-systems-security-engineer",
            "design" to "founding-systems-security-engineer",
        )

        private val COMPONENT_RE = Regex("""\*\*Component:\*\*\s*(\S+)""")

        /** Parses PAPERCLIP_COMPONENT_ROUTES entries of the form `component=urlKey`. */
        fun parseOverrides(raw: String?): Map<String, String> =
            raw.orEmpty().split(',')
                .mapNotNull { entry ->
                    entry.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
                        k.trim().lowercase() to v.trim()
                    }
                }
                .toMap()
    }
}
