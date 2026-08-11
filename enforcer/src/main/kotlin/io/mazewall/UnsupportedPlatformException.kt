package io.mazewall

/**
 * Exception thrown when a requested platform or feature (like Intel CET) is not supported on the current hardware/kernel
 * and the configured [Platform.FallbackBehavior] is set to [Platform.FallbackBehavior.FAIL].
 */
public class UnsupportedPlatformException(message: String) : UnsupportedOperationException(message)
