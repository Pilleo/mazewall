package io.mazewall.portal

import java.io.File

/**
 * Loads a KotlinPoet-generated host stub. Never instantiates a guest `Impl`
 * in the broker; missing stubs fail closed.
 */
public object Portal {
    @JvmStatic
    public fun <T : Any> start(
        type: Class<T>,
        config: PortalWorkerConfig,
    ): PortalService<T> =
        start(type) {
            ProcessBroker(
                poolSize = 1,
                callTimeoutMs = config.callTimeout.toMillis(),
                workerClasspath = config.classpath.joinToString(File.pathSeparator),
                workerExtraJvmArgs =
                    listOf(
                        "-Dio.mazewall.portal.worker.dispatchers=" +
                            "${type.name}=${config.implementationClassName};" +
                            dispatcherName(type),
                    ),
            ).also { it.start() }
        }

    internal fun <T : Any> start(
        type: Class<T>,
        clientFactory: () -> PortalClient,
    ): PortalService<T> {
        val client = clientFactory()
        return try {
            PortalService(create(type, client), client)
        } catch (failure: Throwable) {
            (client as? AutoCloseable)?.close()
            throw failure
        }
    }

    @JvmStatic
    public fun <T : Any> create(
        type: Class<T>,
        client: PortalClient,
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
                stubClass.getConstructor(PortalClient::class.java)
            } catch (e: NoSuchMethodException) {
                throw PortalCallException(
                    "Generated stub $stubName must have a constructor(PortalClient)",
                    e,
                )
            }
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(client) as T
    }

    private fun dispatcherName(type: Class<*>): String =
        type.packageName.let { pkg ->
            (if (pkg.isEmpty()) "" else "$pkg.") + type.simpleName + "PortalDispatcher"
        }
}
