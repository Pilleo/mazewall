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
        val simpleName = relativeName.replace('$', '_') + suffix
        return if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
    }
}
