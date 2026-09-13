package io.mazewall.portal

/**
 * Recreates only an exception type explicitly declared by the public contract.
 *
 * The worker controls the type name in [PortalRemoteCallException], so this mapper never loads a
 * class by that name and never deserializes a remote throwable. It selects from the host-generated
 * allow-list and uses only public no-argument or message constructors.
 */
public object PortalDeclaredExceptionMapper {
    @JvmStatic
    public fun rethrow(
        remote: PortalRemoteCallException,
        declared: Array<Class<out Throwable>>,
    ): Nothing {
        val mapped = declared
            .singleOrNull { it.name == remote.remoteType }
            ?.let { construct(it, remote.remoteMessage) }
        throw (mapped ?: remote)
    }

    private fun construct(
        type: Class<out Throwable>,
        message: String,
    ): Throwable? =
        runCatching {
            type.getConstructor(String::class.java).newInstance(message)
        }.recoverCatching {
            type.getConstructor().newInstance()
        }.getOrNull()
}
