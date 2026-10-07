package io.mazewall.portal

import io.mazewall.enforcer.api.execution.InvocationHandle

/** Host-selected mapping to a generated service stub. Mapping itself runs in the host. */
public fun interface PortalOperationCall<S, A, R> {
    @Throws(Exception::class)
    public fun invoke(
        service: S,
        arguments: A,
    ): R
}

/** Prepared service binding; owns no independent pool and cannot outlive its runtime. */
public class PreparedPortalOperation<A, R> internal constructor(
    private val submitter: (A) -> InvocationHandle<R>,
) {
    /** Caller delivery/cancellation and physical worker retirement are separate stages. */
    public fun submit(arguments: A): InvocationHandle<R> = submitter(arguments)
}
