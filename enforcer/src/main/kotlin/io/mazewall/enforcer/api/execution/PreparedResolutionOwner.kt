package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Uncompiled
import java.nio.file.Path

/**
 * Owns one resolution until its resources settle or transfer to a runtime.
 *
 * [create] captures one ambient native snapshot before acquiring any file descriptors. Resolve is
 * single-use. The currently supported runtime association is Legacy; Live and confined routes
 * refuse this owner. An unconsumed owner can be retired explicitly. After transfer, closing the
 * owner is inert and the owning runtime's drain provides the physical retirement receipt.
 *
 * A pending retirement may be retried. An unknown retirement retains its history and is never
 * replayed. A newly encountered cleanup [Error] is recorded before it is thrown directly.
 */
public class PreparedResolutionOwner private constructor() : AutoCloseable {
    private val capture = DirectResolutionCapture.create()

    public fun resolve(
        runtime: RuntimeProfile,
        policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>,
        implementation: Class<*>,
        readOnlyPaths: Set<Path>,
    ): PreparedOperationResources = capture.prepare(runtime, policy, implementation, readOnlyPaths)

    /** Observes the current disposition without attempting cleanup. */
    public fun retirement(): PreparedResolutionRetirement = capture.retirementSnapshot()

    /** Attempts at most one eligible retirement pass. */
    public fun retryRetirement(): PreparedResolutionRetirement {
        capture.retire()
        return capture.retirementSnapshot()
    }

    /**
     * If a body has already thrown, language resource cleanup may suppress a newly encountered
     * cleanup error on that body failure instead of making the cleanup error the primary exception.
     *
     * @throws PreparedResolutionPendingException if retirement is still pending.
     * @throws PreparedResolutionUnknownException if an earlier dispatched cleanup has unknown outcome.
     */
    override fun close(): Unit = capture.close()

    public companion object {
        @JvmStatic
        public fun create(): PreparedResolutionOwner = PreparedResolutionOwner()
    }
}

public sealed interface PreparedResolutionRetirement {
    /** The caller-owned resources have physically settled. */
    public data class Complete(
        val history: Throwable?,
    ) : PreparedResolutionRetirement

    /** Work is active or an eligible retirement remains to be attempted. */
    public data class Pending(
        val history: Throwable?,
    ) : PreparedResolutionRetirement

    /** A dispatched cleanup lacks an authoritative successful receipt and is not replayed. */
    public data class Unknown(
        val history: Throwable,
    ) : PreparedResolutionRetirement

    /** The runtime owns the resources; its drain supplies their physical retirement receipt. */
    public data class Transferred(
        val history: Throwable?,
    ) : PreparedResolutionRetirement
}

public sealed class PreparedResolutionPendingException protected constructor() : IllegalStateException("Direct capture retirement is pending")

public sealed class PreparedResolutionUnknownException protected constructor(
    history: Throwable,
) : IllegalStateException("Direct capture retirement is unknown", history)
