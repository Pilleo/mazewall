package io.mazewall.enforcer.api.execution

import java.util.function.Function

/**
 * Immutable declared string metadata (for example correlation labels), copied at caller capture.
 * The trusted installer changes only declared worker context and returns its exact prior-context
 * restoration. It must not acquire transactions/connections or move execution to another thread.
 * Invocation values come only from the supplied map; configured installer collaborators stay stable.
 * Mutable framework objects such as Security Authentication are outside this value contract.
 */
public class InvocationContextSnapshot(
    metadata: Map<String, String>,
    private val installer: Function<Map<String, String>, AutoCloseable>,
) {
    private val metadata = java.util.Map.copyOf(metadata)

    internal fun apply(): AutoCloseable = checkNotNull(installer.apply(metadata)) { "Context installer must return restoration" }
}
