package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.frozenCopy
import io.mazewall.enforcer.diagnostics.requirePlatformThread
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.ffi.internal.ImplementationArtifact
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.RuntimeSnapshot
import java.lang.invoke.MethodType
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Genuine resource acquisitions and measured finished-JAR identity, resolved before review/setup.
 * Compare identities with external trusted expectations; digests never supply review authority.
 * Successful preparation transfers cleanup to the runtime until physical retirement. Closing this
 * value afterwards does not revoke its domain. Unconsumed values must be closed by the caller.
 * O_PATH pins objects, not immutable bytes: validation detects observed changes, but cannot stop
 * concurrent sibling writes, agents/redefinition, existing descriptors or mappings.
 */
public class PreparedOperationResources private constructor(
    public val runtime: RuntimeProfile,
    internal val definition: PolicyDefinition<*>,
    internal val native: RuntimeSnapshot,
    internal val resources: PreparedResourceSet,
    private val artifact: ImplementationArtifact,
    private val strictRecipe: Boolean,
    private val policyJson: String,
) : AutoCloseable {
    private enum class Phase { AVAILABLE, TRANSFERRED, CLOSED }

    private var phase = Phase.AVAILABLE
    public val implementationClass: Class<*> get() = artifact.type
    public val implementationSha256: String = artifact.sha256
    public val resourceGeneration: String = HexFormat.of().formatHex(
        MessageDigest
            .getInstance("SHA-256")
        .digest((resources.generation + "\u0000" + artifact.path.identity + "\u0000" + artifact.type.name + "\u0000" + implementationSha256).toByteArray()),
    )

    /** Revalidates retained object identities and selected content snapshots without installing. */
    @Synchronized
    public fun validate() {
        check(phase != Phase.CLOSED) { "Prepared operation resource owner is closed" }
        resources.validate()
        artifact.validate()
    }

    @Synchronized
    internal fun claim(
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
        engine: RuntimeSnapshot,
        abi: Int,
    ) {
        check(phase == Phase.AVAILABLE) { "Prepared operation resources have already been consumed or closed" }
        require(runtime == policy.runtime && policyJson == policy.toJson()) { "Prepared resources do not match reviewed policy/runtime" }
        check(native.engineIdentity === engine.engineIdentity) { "Prepared resources belong to a different native engine" }
        require(
            binding.implementationSha256 == implementationSha256 && binding.resourceGeneration == resourceGeneration,
        ) { "Reviewed implementation/resource generation does not match actual acquisitions" }
        if (strictRecipe || definition.allowedFsWritePaths.isNotEmpty()) require(abi >= TRUNCATE_ABI) { "Read-only/read-write recipes require Landlock ABI 3+ for truncate coverage" }
        validateMember(binding)
        validate()
        phase = Phase.TRANSFERRED
    }

    private fun validateMember(binding: OperationBinding) {
        when (binding.adapter) {
            OperationAdapter.CALLABLE -> requireMember(checkNotNull(binding.adapterIdentity["entryPoint"]))
            OperationAdapter.SPRING_METHOD -> {
                require(binding.adapterIdentity["className"] == implementationClass.name) { "Reviewed target class differs from actual selected class" }
                requireMember(checkNotNull(binding.adapterIdentity["methodDescriptor"]))
            }
            OperationAdapter.KOOG_TOOL -> throw IllegalArgumentException("Koog actual tool binding belongs to the process adapter")
        }
    }

    private fun requireMember(descriptor: String) {
        val method = requireNotNull(
            implementationClass.methods.singleOrNull { method ->
            method.name + MethodType.methodType(method.returnType, method.parameterTypes.toList()).toMethodDescriptorString() ==
            descriptor
        },
        ) {
            "Reviewed JVM method descriptor does not resolve on actual implementation"
        }
        val source = method.declaringClass.protectionDomain
            ?.codeSource
            ?.location
        require(source != null && source.protocol == "file" && Path.of(source.toURI()).toRealPath() == artifact.path.resolved) {
            "Reviewed method implementation originates outside the selected artifact"
        }
    }

    @Synchronized
    override fun close() {
        if (phase == Phase.AVAILABLE) retire()
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // release every acquisition and report the original cleanup failure
    internal fun retire() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        var failure: Throwable? = null
        try {
            resources.close()
        } catch (problem: Throwable) {
            failure = problem
        }
        try {
            artifact.close()
        } catch (problem: Throwable) {
            failure = CleanupFailures.merge(failure, problem)
        }
        failure?.let { throw it }
    }

    public companion object {
        private const val TRUNCATE_ABI = 3

        /**
         * Resolve existing exact grants and an ordinary finished JAR (maximum 256 MiB).
         * Strict read-only paths must be explicitly readable and cannot overlap writable grants.
         * Future output requires an explicitly allowed existing directory. Directory identities
         * cover the directory object, not an immutable recursive snapshot of its descendants.
         * With writable grants, strict read-only recipes require singly-linked regular files;
         * directory recipes reject because descendant writable aliases cannot be excluded.
         * Nested Boot JARs, exploded origins and custom loaders currently reject explicitly.
         */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught") // acquisition boundary owns all rollback before publication
        public fun resolve(runtime: RuntimeProfile, policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>, implementation: Class<*>, readOnlyPaths: Set<Path>): PreparedOperationResources {
            requirePlatformThread()
            require(policy.allowMmapExec == runtime.allowsExecutableMappings) { "Policy runtime mappings contradict selected runtime" }
            val definition = policy.definition.frozenCopy()
            val native = LinuxNative.snapshot()
            val strictPaths = java.util.Set.copyOf(readOnlyPaths)
            val resources = PreparedResourceSet.capture(definition, native, strictPaths)
            var artifact: ImplementationArtifact? = null
            try {
                val selected = ImplementationArtifact.resolve(implementation, native)
                artifact = selected
                require(resources.allowsRead(selected.path.resolved)) { "Selected implementation artifact lacks an exact or explicitly reviewed directory read grant" }
                return PreparedOperationResources(runtime, definition, native, resources, selected, strictPaths.isNotEmpty(), CanonicalPolicyArtifact.worker(runtime, policy).toJson())
            } catch (failure: Throwable) {
                var primary = failure
                try {
                    artifact?.close()
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
                try {
                    resources.close()
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
                throw primary
            }
        }
    }
}
