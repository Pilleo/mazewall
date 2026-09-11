package io.mazewall.portal

/**
 * Canonical binary names for generated portal artifacts.
 *
 * Nested service names include every enclosing class so independently declared
 * services with the same simple name never target the same generated class.
 */
public object PortalGeneratedNames {
    @JvmStatic
    public fun stubBinaryName(service: Class<*>): String = binaryName(service, "PortalStub")

    @JvmStatic
    public fun dispatcherBinaryName(service: Class<*>): String = binaryName(service, "PortalDispatcher")

    private fun binaryName(
        service: Class<*>,
        suffix: String,
    ): String {
        require(!service.isAnonymousClass && !service.isLocalClass) {
            "portal services must have a stable binary name: ${service.name}"
        }
        val packageName = service.packageName
        val relativeName =
            if (packageName.isEmpty()) service.name else service.name.removePrefix("$packageName.")
        // Keep top-level names byte-for-byte compatible. Nested names use a
        // length-prefixed encoding so '$' and '_' cannot collapse distinct
        // JVM binary names onto the same generated class.
        val simpleName = if ('$' !in relativeName) {
            relativeName + suffix
        } else {
            val encoded = relativeName.split('$').joinToString("_") { part ->
                "${part.length}_$part"
            }
            "Nested_${encoded}$suffix"
        }
        return if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
    }
}
