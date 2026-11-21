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
            ?.let { construct(it, remote.remoteMessage, remote) }
        throw (mapped ?: remote)
    }

    private fun construct(
        type: Class<out Throwable>,
        message: String,
        remote: PortalRemoteCallException,
    ): Throwable? {
        val messageCtor =
            try {
                type.getConstructor(String::class.java)
            } catch (_: NoSuchMethodException) {
                null
            }
        if (messageCtor != null) {
            return invokeConstructor(remote) { messageCtor.newInstance(message) }
        }
        val noArgCtor =
            try {
                type.getConstructor()
            } catch (_: NoSuchMethodException) {
                null
            }
        return noArgCtor?.let { ctor -> invokeConstructor(remote) { ctor.newInstance() } }
    }

    private fun invokeConstructor(
        remote: PortalRemoteCallException,
        instantiate: () -> Throwable,
    ): Throwable? =
        try {
            instantiate()
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            val target = wrapped.targetException ?: wrapped
            if (target !== remote && target.cause !== remote && remote !in target.suppressed) {
                target.addSuppressed(remote)
            }
            throw target
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }
}
