package io.mazewall.enforcer.api.execution

import java.time.Duration

/** A snapshot of the retained canonical runtime acquisition. */
public sealed interface ManagedRuntimeAcquisitionStatus {
    public data object NotStarted : ManagedRuntimeAcquisitionStatus

    public data object Starting : ManagedRuntimeAcquisitionStatus

    public data object Active : ManagedRuntimeAcquisitionStatus

    public data class Pending(
        public val history: Throwable?,
    ) : ManagedRuntimeAcquisitionStatus

    public data class Complete(
        public val history: Throwable?,
    ) : ManagedRuntimeAcquisitionStatus

    public data class Unknown(
        public val history: Throwable,
    ) : ManagedRuntimeAcquisitionStatus
}

/** The original runtime still has outstanding physical retirement work. */
public class RuntimeAcquisitionPendingException(
    public val status: ManagedRuntimeAcquisitionStatus.Pending,
) : IllegalStateException("Runtime acquisition retirement remains pending", status.history)

/** The original installation has an ambiguous outcome. */
public class RuntimeAcquisitionUnknownException(
    public val status: ManagedRuntimeAcquisitionStatus.Unknown,
) : IllegalStateException("Runtime acquisition installation outcome is unknown", status.history)

/** Owns the canonical HotSpot process baseline and its original managed runtime. */
public class ManagedRuntimeAcquisitionOwner private constructor(
    request: io.mazewall.ffi.internal.CheckedBootstrapRequest?,
) : AutoCloseable {
    private val protocol = ManagedRuntime.newCanonicalAcquisition(request)

    private constructor() : this(null)

    public companion object {
        @JvmStatic
        public fun create(): ManagedRuntimeAcquisitionOwner = ManagedRuntimeAcquisitionOwner()

        internal fun createChecked(request: io.mazewall.ffi.internal.CheckedBootstrapRequest): ManagedRuntimeAcquisitionOwner = ManagedRuntimeAcquisitionOwner(request)
    }

    public fun start(
        preparationTimeout: Duration,
        closeTimeout: Duration,
    ): ManagedRuntime = protocol.start(preparationTimeout, closeTimeout)

    /**
     * Returns whether [candidate] is the exact runtime retained by this owner, including after
     * retirement. This observation does not establish readiness, admission or physical completion,
     * and grants no lease. A null or unrelated runtime returns false.
     */
    public fun ownsRuntime(candidate: ManagedRuntime?): Boolean = candidate != null && protocol.matchesRuntime(candidate)

    public fun status(): ManagedRuntimeAcquisitionStatus = protocol.status()

    public fun retire(timeout: Duration): ManagedRuntimeAcquisitionStatus = protocol.retire(timeout)

    override fun close(): Unit = protocol.close()
}
