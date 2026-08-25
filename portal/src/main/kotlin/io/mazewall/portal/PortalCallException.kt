package io.mazewall.portal

/**
 * Fail-closed RPC failure. The broker never falls back to in-process guest code.
 */
public class PortalCallException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
