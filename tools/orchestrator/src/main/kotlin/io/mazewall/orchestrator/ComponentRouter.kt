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
        /** Workhorse for unrouted components. Jules stays available but is not default. */
        const val DEFAULT_ADAPTER = "vibe"

        /** Opt-in table (env): component -> roster urlKey. Empty = Jules for all. */
        val DEFAULT_ROUTES: Map<String, String> = emptyMap()
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
