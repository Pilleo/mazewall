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
 * Genuine resource acquisitions and measured deployment identity, resolved before review/setup.
 * Compare identities with external trusted expectations; digests never supply review authority.
 * Successful preparation transfers cleanup to the runtime until physical retirement. Closing this
 * value afterwards does not revoke its domain. Unconsumed values must be closed by the caller.
 * O_PATH pins objects, not immutable bytes: validation detects observed changes, but cannot stop
 * concurrent sibling writes, agents/redefinition, existing descriptors or mappings.
 */
public class PreparedOperationResources private constructor(
    public val runtime: RuntimeProfile,
    internal val definition: PolicyDefinition<*>,
    private val legacyNative: RuntimeSnapshot?,
    private val legacyResources: PreparedResourceSet?,
    private val artifact: ImplementationArtifact?,
    private val strictRecipe: Boolean,
    private val policyJson: String,
    private val directOwner: DirectResolutionCapture?,
    private val directMetadata: DirectResolutionCapture.Metadata?,
) : AutoCloseable {
    private constructor(
        runtime: RuntimeProfile,
        definition: PolicyDefinition<*>,
        native: RuntimeSnapshot,
        resources: PreparedResourceSet,
        artifact: ImplementationArtifact,
        strictRecipe: Boolean,
        policyJson: String,
    ) :
        this(runtime, definition, native, resources, artifact, strictRecipe, policyJson, null, null)

    private constructor(owner: DirectResolutionCapture, metadata: DirectResolutionCapture.Metadata) :
        this(metadata.runtime, metadata.definition, null, null, null, metadata.strict, metadata.policyJson, owner, metadata) {
        check(owner.permitsPublication(metadata))
    }

    internal val native: RuntimeSnapshot get() = checkNotNull(legacyNative) { "Direct result has no native alias" }
    internal val resources: PreparedResourceSet get() = checkNotNull(legacyResources) { "Direct result has no resource alias" }

    internal fun isDirectResult(): Boolean = directOwner != null

    internal fun attachDirectPermit(
        runtime: ManagedRuntime,
        permit: Any,
    ): Boolean = directOwner?.attachDirectPermit(runtime, permit, this) == true

    internal fun beginDirectValidation(
        runtime: ManagedRuntime,
        permit: Any,
        engine: RuntimeSnapshot,
    ): Boolean = directOwner?.beginDirectValidation(runtime, permit, this, engine) == true

    internal fun matchesDirectResources(
        permit: Any,
        resources: Any,
    ): Boolean = directOwner?.matchesDirectResources(permit, this, resources) == true

    internal fun requireDirectSource(
        runtime: ManagedRuntime,
        permit: Any,
        resources: PreparedResourceSet,
    ) = checkNotNull(directOwner).requireDirectSource(runtime, permit, this, resources)

    internal fun allocateDirectLandlock(
        runtime: ManagedRuntime,
        permit: Any,
        environment: io.mazewall.enforcer.api.InstallationEnvironment,
    ): io.mazewall.landlock.PreparedLandlock = checkNotNull(directOwner).allocateDirectLandlock(runtime, permit, this, environment)

    internal fun awaitDirectValidators(action: DirectClaimAction) = checkNotNull(directOwner).awaitDirectValidators(this, action)

    internal fun retireDirect(action: DirectClaimAction) = checkNotNull(directOwner).retireDirect(this, action)

    internal fun requireDirectRetirementReady(action: DirectClaimAction) = checkNotNull(directOwner).requireDirectRetirementReady(this, action)

    internal fun directRetirementPending(): Boolean = directOwner?.directRetirementPending(this) == true

    private enum class Phase { AVAILABLE, TRANSFERRED, CLOSED }

    private var phase = Phase.AVAILABLE
    private var retirementStarted = false
    public val implementationClass: Class<*> get() = artifact?.type ?: checkNotNull(directMetadata).implementation
    public val implementationSha256: String = artifact?.sha256 ?: checkNotNull(directMetadata).sha256
    public val resourceGeneration: String = directMetadata?.generation ?: HexFormat.of().formatHex(
        MessageDigest
            .getInstance(
            "SHA-256",
        ).digest((resources.generation + "\u0000" + checkNotNull(artifact).generation + "\u0000" + implementationClass.name + "\u0000" + implementationSha256).toByteArray()),
    )

    /** Revalidates retained object identities and selected content snapshots without installing. */
    public fun validate() {
        val owner = directOwner
        if (owner != null) {
            owner.validate(this)
        } else {
            synchronized(this) {
                check(!retirementStarted) { "Prepared operation resource owner is closed" }
                resources.validate()
                checkNotNull(artifact).validate()
            }
        }
    }

    @Synchronized
    internal fun claim(
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
        engine: RuntimeSnapshot,
        abi: Int,
    ) {
        check(directOwner == null) { "Direct result runtime claim is not available" }
        check(phase == Phase.AVAILABLE && !retirementStarted) { "Prepared operation resources have already been consumed or closed" }
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

    internal fun claimDirect(
        runtimeOwner: ManagedRuntime,
        permit: Any,
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
        engine: RuntimeSnapshot,
        abi: Int,
    ) {
        val owner = checkNotNull(directOwner)
        owner.claimDirect(runtimeOwner, permit, this, binding, policy, engine, abi)
    }

    internal fun validateDirectClaim(
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
        abi: Int,
    ) {
        require(runtime == policy.runtime && policyJson == policy.toJson()) { "Prepared resources do not match reviewed policy/runtime" }
        require(
            binding.implementationSha256 == implementationSha256 && binding.resourceGeneration == resourceGeneration,
        ) { "Reviewed implementation/resource generation does not match actual acquisitions" }
        if (strictRecipe || definition.allowedFsWritePaths.isNotEmpty()) require(abi >= TRUNCATE_ABI) { "Read-only/read-write recipes require Landlock ABI 3+ for truncate coverage" }
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
        checkNotNull(artifact).requireMemberOrigin(method.declaringClass)
    }

    override fun close() {
        val owner = directOwner
        if (owner != null) {
            when (val outcome = owner.retire()) {
                is DirectResolutionCapture.Retirement.Complete -> Unit
                is DirectResolutionCapture.Retirement.Pending -> throw DirectPendingException()
                is DirectResolutionCapture.Retirement.Unknown -> throw DirectUnknownException(outcome.history)
                DirectResolutionCapture.Retirement.Transferred -> Unit
            }
        } else {
            synchronized(this) { if (phase == Phase.AVAILABLE) retireLegacy() }
        }
    }

    internal fun retire() {
        if (directOwner != null) close() else synchronized(this) { retireLegacy() }
    }

    @Suppress("TooGenericExceptionCaught") // release every acquisition and report the original cleanup failure
    private fun retireLegacy() {
        if (phase == Phase.CLOSED) return
        retirementStarted = true
        var failure: Throwable? = null
        try {
            resources.close()
        } catch (problem: Throwable) {
            failure = problem
        }
        try {
            checkNotNull(artifact).close()
        } catch (problem: Throwable) {
            failure = CleanupFailures.merge(failure, problem)
        }
        failure?.let { throw it }
        phase = Phase.CLOSED
    }

    public companion object {
        private const val TRUNCATE_ABI = 3

        internal fun direct(
            owner: DirectResolutionCapture,
            metadata: DirectResolutionCapture.Metadata,
        ): PreparedOperationResources = PreparedOperationResources(owner, metadata)

        /**
         * Resolve exact grants and an ordinary/Boot finished JAR or explicit exploded tree (256 MiB).
         * Strict read-only paths must be explicitly readable and cannot overlap writable grants.
         * Future output requires an explicitly allowed existing directory. Directory identities
         * cover the directory object, not an immutable recursive snapshot of its descendants.
         * With writable grants, strict read-only recipes require singly-linked regular files;
         * directory recipes reject because descendant writable aliases cannot be excluded.
         * Exploded trees require explicit directory reads, no descendant symlinks and bounded
         * complete snapshots. Boot supports the pinned 4.1.1 launcher hierarchy in one finished
         * outer JAR, application classes and one-level stored nested libraries. Other loaders,
         * versioned resources and unsupported packaging reject; no dependency on Spring is needed.
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
                val selected = ImplementationArtifact.resolve(implementation, native, resources::allowsExplicitDirectoryRead)
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
