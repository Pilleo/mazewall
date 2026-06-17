package io.mazewall

/**
 * Exception thrown when a requested security feature is not supported by the running Linux kernel
 * and the configured [Platform.FallbackBehavior] is set to [Platform.FallbackBehavior.FAIL].
 */
public class UnsupportedKernelFeatureException(message: String) : UnsupportedOperationException(message)
