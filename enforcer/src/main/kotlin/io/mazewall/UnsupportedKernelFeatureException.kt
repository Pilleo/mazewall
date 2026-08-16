package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

/**
 * Exception thrown when a requested security feature is not supported by the running Linux kernel
 * and the configured [Platform.FallbackBehavior] is set to [Platform.FallbackBehavior.FAIL].
 */
public class UnsupportedKernelFeatureException(message: String) : UnsupportedOperationException(message)
