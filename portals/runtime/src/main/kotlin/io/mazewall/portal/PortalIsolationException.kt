package io.mazewall.portal

/** Base unchecked failure for service-isolation configuration and lifecycle errors. */
public open class PortalIsolationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

public class PortalServiceNotFoundException(
    contractName: String,
    id: PortalServiceId? = null,
) : PortalIsolationException(
        if (id == null) {
            "no portal service manifest binds contract $contractName"
        } else {
            "no portal service manifest binds contract $contractName with ID ${id.value}"
        },
    )

public class PortalServiceAmbiguousException(
    contractName: String,
    candidates: List<PortalServiceId>,
) : PortalIsolationException(
        "portal contract $contractName has multiple bindings: ${candidates.joinToString { it.value }}; select a PortalServiceId explicitly",
    )

public class PortalWorkerArtifactUnavailableException(
    serviceId: PortalServiceId,
    artifact: String,
    cause: Throwable? = null,
) : PortalIsolationException("portal service ${serviceId.value} worker artifact is unavailable: $artifact", cause)

public class PortalWorkerStartupException(
    serviceId: PortalServiceId,
    cause: Throwable,
) : PortalIsolationException("portal service ${serviceId.value} worker failed to start", cause)

public class PortalRuntimeClosedException : PortalIsolationException("portal runtime is closed")

public class PortalStateLostException(
    group: String,
) : PortalIsolationException("portal isolation group $group lost state after an invocation with unknown completion")
