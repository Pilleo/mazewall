package io.mazewall.portal

/** Host-selected mapping to a generated service stub. Mapping itself runs in the host. */
public fun interface PortalOperationCall<S, A, R> {
    @Throws(Exception::class)
    public fun invoke(
        service: S,
        arguments: A,
    ): R
}
