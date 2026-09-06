package io.mazewall.portal

/**
 * Host-side request channel used by generated portal stubs.
 *
 * Implementations own worker lifecycle and capability transfer; generated code only
 * serializes boundary values and invokes a protocol method.
 */
public interface PortalClient {
    public fun invoke(
        methodId: Int,
        payload: ByteArray,
        vararg granted: Capability.ReadFd,
    ): ByteArray
}
