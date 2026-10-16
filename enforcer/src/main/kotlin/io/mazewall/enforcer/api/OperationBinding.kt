package io.mazewall.enforcer.api

/** Adapter identity fields are data until matched against trusted application configuration. */
public enum class OperationAdapter { CALLABLE, SPRING_METHOD, KOOG_TOOL }

public enum class CoverageFacet { SYSCALLS, FILE_PATHS, NETWORK_DESTINATIONS, ASYNC_EXECUTION, PROCESS_THREADS }

public class OperationBinding(
    public val operationId: String,
    public val adapter: OperationAdapter,
    public val implementationSha256: String,
    public val resourceGeneration: String,
    adapterIdentity: Map<String, String>,
) {
    public val adapterIdentity: Map<String, String> = java.util.Map.copyOf(adapterIdentity)

    init {
        require(operationId.isNotBlank() && resourceGeneration.isNotBlank()) { "Operation identity and resource generation are required" }
        requireSha256(implementationSha256)
        val fields = when (adapter) {
            OperationAdapter.CALLABLE -> setOf("entryPoint")
            OperationAdapter.SPRING_METHOD -> setOf("beanName", "className", "methodDescriptor")
            OperationAdapter.KOOG_TOOL -> setOf("toolName", "contractId")
        }
        require(this.adapterIdentity.keys == fields && this.adapterIdentity.values.all { it.isNotBlank() }) { "Adapter identity does not match its versioned schema" }
    }

    internal fun matches(other: OperationBinding): Boolean =
        operationId == other.operationId &&
            adapter == other.adapter &&
            implementationSha256 == other.implementationSha256 &&
            resourceGeneration == other.resourceGeneration &&
        adapterIdentity == other.adapterIdentity
}

private const val SHA256_HEX_LENGTH = 64

internal fun requireSha256(value: String) {
    require(value.length == SHA256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Expected lowercase SHA-256 identity" }
}
