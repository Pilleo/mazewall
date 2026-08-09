package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

/**
 * Exception thrown when a requested platform or feature (like Intel CET) is not supported on the current hardware/kernel
 * and the configured [Platform.FallbackBehavior] is set to [Platform.FallbackBehavior.FAIL].
 */
public class UnsupportedPlatformException(message: String) : UnsupportedOperationException(message)
