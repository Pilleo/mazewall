package io.mazewall.portal

/**
 * Loads a KotlinPoet-generated host stub. Never instantiates a guest `Impl`
 * in the broker; missing stubs fail closed.
 */
public object Portal {
    @JvmStatic
    public fun <T : Any> create(
        type: Class<T>,
        broker: ProcessBroker,
    ): T {
        val stubName = type.name + "PortalStub"
        val stubClass =
            try {
                Class.forName(stubName, false, type.classLoader)
            } catch (e: ClassNotFoundException) {
                throw PortalCallException(
                    "Missing generated portal stub $stubName for ${type.name}. " +
                        "Generate it with :portal-codegen. Guest implementations are never loaded in the broker.",
                    e,
                )
            }
        require(type.isAssignableFrom(stubClass)) {
            "$stubName does not implement ${type.name}"
        }
        require(!stubClass.name.endsWith("Impl")) {
            "refusing to load ${stubClass.name} as a portal stub"
        }
        val ctor =
            try {
                stubClass.getConstructor(ProcessBroker::class.java)
            } catch (e: NoSuchMethodException) {
                throw PortalCallException(
                    "Generated stub $stubName must have a constructor(ProcessBroker)",
                    e,
                )
            }
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(broker) as T
    }
}
