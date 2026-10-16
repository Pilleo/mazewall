package io.mazewall.profiler.tierE.tools

/** Profile-selected experiments use one explicit local Docker endpoint, not an ambient Podman context. */
public object TierEProfileEndpoint {
    public fun select(
        environment: Map<String, String> = System.getenv(),
        override: String? = System.getProperty("mazewall.tier-e.endpoint"),
    ): String {
        val explicit = override?.takeIf(String::isNotBlank)
        val selected = explicit ?: run {
            val primary = environment["MAZEWALL_DOCKER_HOST"]?.takeIf(String::isNotBlank)
            val legacy = environment["DOCKER_HOST"]?.takeIf(String::isNotBlank)
            require(primary == null || legacy == null || primary == legacy) {
                "Conflicting Docker endpoint aliases; pass -PtierEEndpoint explicitly or resolve the operator configuration"
            }
            primary ?: "unix:///var/run/docker.sock"
        }
        require(selected.startsWith("unix://")) { "Tier E profile requires an explicitly local Docker socket" }
        return selected
    }
}
