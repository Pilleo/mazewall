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
import io.mazewall.ffi.internal.DeploymentOrigin
import io.mazewall.ffi.internal.ExplodedArtifact
import io.mazewall.ffi.internal.ImplementationArtifact
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import java.io.File
import java.lang.invoke.MethodType
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal class DirectPendingException : PreparedResolutionPendingException()

internal class DirectUnknownException(
    history: Throwable,
) : PreparedResolutionUnknownException(history)

/** Internal caller-owned grant capture; artifact and result publication are not implemented. */
internal class DirectResolutionCapture private constructor() : AutoCloseable {
    internal sealed interface Retirement {
        data class Complete(
            val history: Throwable?,
        ) : Retirement

        data class Pending(
            val history: Throwable?,
        ) : Retirement

        data class Unknown(
            val history: Throwable,
        ) : Retirement

        data object Transferred : Retirement
    }

    private enum class Phase { NEW, RESOLVING, RETAINED, CLAIMED, RETIRING, COMPLETE, UNKNOWN }

    private val native = LinuxNative.snapshot()
    private val gate = Any()
    private val slots = mutableListOf<PreparedPath.DirectSlot>()
    private var phase = Phase.NEW
    private var actor: Thread? = null
    private var history: Throwable? = null

    private var deliveredResolveError: Error? = null
    private var deliveredCleanupError: Error? = null
    private var retainedCleanupFailure: Throwable? = null
    private var retainedPreviousHistory: Throwable? = null
    private var staged: Metadata? = null
    private var associated: PreparedOperationResources? = null
    private var captured: Captured? = null
    private var validators = 0
    private var retirementRequested = false
    private var runtimeRetirementReserved = false
    private var claimedPermit: Any? = null
    private var claimedRuntime: ManagedRuntime? = null
    private var validatedClaimActor: Thread? = null
    private var validatedClaimPermit: Any? = null
    private var claimValidationActor: Thread? = null
    private var claimValidationPermit: Any? = null
    private val afterValidationPermit: () -> Unit = {}

    internal class Metadata(
        val runtime: RuntimeProfile,
        val definition: PolicyDefinition<*>,
        val strict: Boolean,
        val policyJson: String,
        val implementation: Class<*>,
        val sha256: String,
        val generation: String,
    )

    private class Captured(
        val resources: PreparedResourceSet,
        val implementation: Class<*>?,
        val root: PreparedPath?,
        val entries: Map<String, PreparedPath>?,
        val definition: PolicyDefinition<*>,
        val strict: Boolean,
    )

    @Suppress("TooGenericExceptionCaught") // The original carrier retains prepublication failure and every typed slot.
    fun prepare(
        runtime: RuntimeProfile,
        policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>,
        implementation: Class<*>,
        strict: Set<Path>,
    ): PreparedOperationResources {
        requirePlatformThread()
        require(policy.allowMmapExec == runtime.allowsExecutableMappings)
        synchronized(gate) {
            check(phase == Phase.NEW)
            phase = Phase.RESOLVING
            actor = Thread.currentThread()
        }
        try {
            val data = captureBody(policy.definition, strict, implementation)
            val root = checkNotNull(data.root)
            val artifact = ImplementationArtifact.metadataDirect(implementation, root, data.entries)
            val generation = generation(data.resources.generation, artifact.second, implementation, artifact.first)
            val metadata = Metadata(runtime, data.definition, data.strict, CanonicalPolicyArtifact.worker(runtime, policy).toJson(), implementation, artifact.first, generation)
            synchronized(gate) {
                check(phase == Phase.RESOLVING && actor === Thread.currentThread())
                staged = metadata
            }
            val result = PreparedOperationResources.direct(this, metadata)
            synchronized(gate) {
                check(phase == Phase.RESOLVING && actor === Thread.currentThread() && staged === metadata && associated == null)
                captured = data
                associated = result
                staged = null
                actor = null
                phase = Phase.RETAINED
            }
            return result
        } catch (failure: Throwable) {
            synchronized(gate) {
                history = failure
                deliveredResolveError = failure as? Error
            }
            throw failure
        } finally {
            synchronized(gate) {
                if (phase == Phase.RESOLVING) {
                staged = null
                actor = null
                phase = Phase.RETAINED
            }
            }
        }
    }

    fun permitsPublication(metadata: Metadata): Boolean =
        synchronized(gate) {
        phase == Phase.RESOLVING && actor === Thread.currentThread() && staged === metadata && associated == null
    }

    fun attachDirectPermit(
        runtime: ManagedRuntime,
        permit: Any,
        result: PreparedOperationResources,
    ): Boolean =
        synchronized(gate) {
            if (phase != Phase.RETAINED ||
                retirementRequested ||
                associated !== result ||
                claimedPermit != null
            ) {
                false
            } else if (
                validatedClaimActor !== Thread.currentThread() ||
                validatedClaimPermit !== permit ||
                !runtime.matchesDirectPermitLocked(permit, result, DirectClaimAction.CLAIM)
            ) {
                false
            } else {
                claimedRuntime = runtime
                claimedPermit = permit
                validatedClaimActor = null
                validatedClaimPermit = null
                phase = Phase.CLAIMED
                true
            }
        }

    fun beginDirectValidation(
        runtime: ManagedRuntime,
        permit: Any,
        result: PreparedOperationResources,
        engine: io.mazewall.platform.native.RuntimeSnapshot,
    ): Boolean =
        synchronized(gate) {
            if (phase != Phase.RETAINED ||
                retirementRequested ||
                associated !== result ||
                claimValidationActor != null ||
                native.engineIdentity !== engine.engineIdentity ||
                !runtime.matchesDirectPermitLocked(permit, result, DirectClaimAction.CLAIM)
            ) {
                false
            } else {
                validators++
                claimValidationActor = Thread.currentThread()
                claimValidationPermit = permit
                true
            }
        }

    fun matchesDirectResources(
        permit: Any,
        result: PreparedOperationResources,
        resources: Any,
    ): Boolean =
        synchronized(gate) {
            val data = captured
            phase == Phase.CLAIMED && claimedPermit === permit && associated === result && data != null && data.resources === resources
        }

    fun requireDirectSource(
        runtime: ManagedRuntime,
        permit: Any,
        result: PreparedOperationResources,
        resources: PreparedResourceSet,
    ) {
        check(runtime.matchesDirectPermit(permit, result, DirectClaimAction.PREPARE_LANDLOCK))
        val data = synchronized(gate) {
            val current = checkNotNull(captured)
            check(phase == Phase.CLAIMED && claimedRuntime === runtime && claimedPermit === permit && associated === result && current.resources === resources)
            current
        }
        resources.requireSource(data.definition, native)
    }

    fun allocateDirectLandlock(
        runtime: ManagedRuntime,
        permit: Any,
        result: PreparedOperationResources,
        environment: io.mazewall.enforcer.api.InstallationEnvironment,
    ): io.mazewall.landlock.PreparedLandlock {
        check(runtime.matchesDirectPermit(permit, result, DirectClaimAction.ALLOCATE_LANDLOCK))
        val resources = synchronized(gate) {
            check(phase == Phase.CLAIMED && claimedPermit === permit && claimedRuntime === runtime && associated === result)
            checkNotNull(captured).resources
        }
        return io.mazewall.landlock.PreparedLandlock
            .allocateDirect(resources, environment, runtime, permit, result)
    }

    fun validate(result: PreparedOperationResources) {
        val data = synchronized(gate) {
            check((phase == Phase.RETAINED && !retirementRequested || phase == Phase.CLAIMED && !runtimeRetirementReserved) && associated === result)
            validators++
            checkNotNull(captured)
        }
        try {
            afterValidationPermit()
            data.resources.validate()
            ImplementationArtifact.verifyDirect(checkNotNull(data.implementation), checkNotNull(data.root), data.entries)
            val current = ImplementationArtifact.metadataDirect(data.implementation, data.root, data.entries)
            check(current.first == result.implementationSha256)
            check(generation(data.resources.generation, current.second, data.implementation, current.first) == result.resourceGeneration)
        } finally {
            synchronized(gate) {
                validators--
                (gate as java.lang.Object).notifyAll()
            }
        }
    }

    fun claimDirect(
        runtime: ManagedRuntime,
        permit: Any,
        result: PreparedOperationResources,
        binding: OperationBinding,
        policy: CanonicalPolicyArtifact.Worker,
        engine: io.mazewall.platform.native.RuntimeSnapshot,
        abi: Int,
    ) {
        check(runtime.beginDirectValidation(permit, result, binding, policy, engine, abi))
        try {
            val data = synchronized(gate) {
                check(claimValidationActor === Thread.currentThread() && claimValidationPermit === permit)
                checkNotNull(captured)
            }
            result.validateDirectClaim(binding, policy, abi)
            afterValidationPermit()
            check(runtime.matchesDirectClaim(permit, result, binding, policy, engine, abi))
            data.resources.validate()
            check(runtime.matchesDirectClaim(permit, result, binding, policy, engine, abi))
            val implementation = checkNotNull(data.implementation)
            val root = checkNotNull(data.root)
            ImplementationArtifact.verifyDirect(implementation, root, data.entries)
            check(runtime.matchesDirectClaim(permit, result, binding, policy, engine, abi))
            val current = ImplementationArtifact.metadataDirect(implementation, root, data.entries)
            check(current.first == result.implementationSha256 && generation(data.resources.generation, current.second, implementation, current.first) == result.resourceGeneration)
            val descriptor = when (binding.adapter) {
                OperationAdapter.CALLABLE -> checkNotNull(binding.adapterIdentity["entryPoint"])
                OperationAdapter.SPRING_METHOD -> {
                    require(binding.adapterIdentity["className"] == implementation.name)
                    checkNotNull(binding.adapterIdentity["methodDescriptor"])
                }
                OperationAdapter.KOOG_TOOL -> throw IllegalArgumentException("Koog actual tool binding belongs to the process adapter")
            }
            val method = requireNotNull(
                implementation.methods.singleOrNull {
                it.name + MethodType.methodType(it.returnType, it.parameterTypes.toList()).toMethodDescriptorString() == descriptor
            },
            )
            val declaring = method.declaringClass
            require(declaring.classLoader === implementation.classLoader && DeploymentOrigin.of(declaring) == DeploymentOrigin.of(implementation))
            check(runtime.matchesDirectClaim(permit, result, binding, policy, engine, abi))
            ImplementationArtifact.verifyDirect(declaring, root, data.entries)
            synchronized(gate) {
                check(phase == Phase.RETAINED && !retirementRequested && associated === result && validatedClaimActor == null)
                validatedClaimActor = Thread.currentThread()
                validatedClaimPermit = permit
            }
            check(runtime.attachDirectPermit(permit, result)) { "Direct claim is no longer available" }
        } finally {
            synchronized(gate) {
                if (validatedClaimActor === Thread.currentThread() && validatedClaimPermit === permit) {
                    validatedClaimActor = null
                    validatedClaimPermit = null
                }
                check(claimValidationActor === Thread.currentThread() && claimValidationPermit === permit)
                claimValidationActor = null
                claimValidationPermit = null
                validators--
                (gate as java.lang.Object).notifyAll()
            }
        }
    }

    fun awaitDirectValidators(
        result: PreparedOperationResources,
        action: DirectClaimAction,
    ) {
        check(action == DirectClaimAction.ROLLBACK_RULESET || action == DirectClaimAction.RETIRE_DOMAIN)
        val runtime = synchronized(gate) { checkNotNull(claimedRuntime) }
        val permit = synchronized(gate) { checkNotNull(claimedPermit) }
        check(runtime.matchesDirectPermit(permit, result, action))
        var interrupted = false
        synchronized(gate) {
            check(phase == Phase.CLAIMED && associated === result)
            runtimeRetirementReserved = true
            while (validators != 0) {
                try {
                    (gate as java.lang.Object).wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the dispatched owner and its original close failure.
    fun retireDirect(
        result: PreparedOperationResources,
        action: DirectClaimAction,
    ) {
        check(action == DirectClaimAction.ROLLBACK_RULESET || action == DirectClaimAction.RETIRE_DOMAIN)
        val runtime = synchronized(gate) { checkNotNull(claimedRuntime) }
        val permit = synchronized(gate) { checkNotNull(claimedPermit) }
        check(runtime.matchesDirectCarrierRetirement(permit, result, action))
        synchronized(gate) {
            check(associated === result && phase == Phase.CLAIMED && validators == 0)
            phase = Phase.RETIRING
            actor = Thread.currentThread()
        }
        for (slot in slots.asReversed()) {
            if (!runtime.matchesDirectCarrierRetirement(permit, result, action)) {
                synchronized(gate) {
                    phase = Phase.CLAIMED
                    actor = null
                }
                throw DirectPendingException()
            }
            try {
                slot.closeOwned()
            } catch (failure: Throwable) {
                val merged = CleanupFailures.merge(history, failure)
                synchronized(gate) {
                    history = merged
                    phase = Phase.UNKNOWN
                    actor = null
                }
                throw failure
            }
        }
        synchronized(gate) {
            phase = Phase.COMPLETE
            actor = null
        }
    }

    fun requireDirectRetirementReady(
        result: PreparedOperationResources,
        action: DirectClaimAction,
    ) {
        check(action == DirectClaimAction.ROLLBACK_RULESET || action == DirectClaimAction.RETIRE_DOMAIN)
        val runtime = synchronized(gate) { checkNotNull(claimedRuntime) }
        val permit = synchronized(gate) { checkNotNull(claimedPermit) }
        check(runtime.matchesDirectCarrierRetirement(permit, result, action))
        synchronized(gate) { check(phase == Phase.CLAIMED && associated === result && validators == 0) }
    }

    fun directRetirementPending(result: PreparedOperationResources): Boolean = synchronized(gate) { associated === result && phase == Phase.CLAIMED && runtimeRetirementReserved }

    private fun generation(
        resources: String,
        artifact: String,
        implementation: Class<*>,
        sha256: String,
    ): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((resources + "\u0000" + artifact + "\u0000" + implementation.name + "\u0000" + sha256).toByteArray()))

    fun capture(
        policy: PolicyDefinition<*>,
        strict: Set<Path>,
    ) = capture(policy, strict, null)

    @Suppress("TooGenericExceptionCaught") // Original caller retains every slot and the original failure.
    fun capture(
        policy: PolicyDefinition<*>,
        strict: Set<Path>,
        implementation: Class<*>?,
    ) {
        synchronized(gate) {
            check(phase == Phase.NEW)
            phase = Phase.RESOLVING
            actor = Thread.currentThread()
        }
        try {
            captureBody(policy, strict, implementation)
        } catch (failure: Throwable) {
            synchronized(gate) {
                history = failure
                deliveredResolveError = failure as? Error
            }
            throw failure
        } finally {
            synchronized(gate) {
                actor = null
                phase = Phase.RETAINED
            }
        }
    }

    private fun captureBody(
        policy: PolicyDefinition<*>,
        strict: Set<Path>,
        implementation: Class<*>?,
    ): Captured {
            val frozen = policy.frozenCopy()
            require(frozen.enforceLandlock)
            val reads = frozen.allowedFsReadPaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
            val writes = frozen.allowedFsWritePaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
            val strictCopy = java.util.Set
                .copyOf(strict)
                .toList()
            val explicit = (reads + writes).map { path ->
                PreparedResourceSet.Grant(open(path), path in reads, path in writes, false, PreparedResourceSet.GrantPurpose.Explicit)
            }
            val strictPaths = strictCopy.map { open(it) }
            PreparedResourceSet.validateDirect(frozen, explicit, strictPaths)
            val runtime = linkedSetOf(Path.of(checkNotNull(System.getProperty("java.home"))))
            System
                .getProperty("java.class.path", "")
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .forEach { runtime.add(Path.of(it)) }
            val automatic = runtime.map { PreparedResourceSet.Grant(open(it), true, false, true, PreparedResourceSet.GrantPurpose.AutomaticRuntime) }
            val resources = PreparedResourceSet.buildDirect(frozen, native, explicit, strictPaths, automatic)
            var root: PreparedPath? = null
            var entries: Map<String, PreparedPath>? = null
            if (implementation != null) {
                val origin = DeploymentOrigin.of(implementation)
                root = open(origin.requested)
                ImplementationArtifact.validateRootDirect(implementation, root)
                entries = if (root.directory) {
                    require(resources.allowsExplicitDirectoryRead(root.resolved))
                    ExplodedArtifact.list(root.resolved).mapValues { (_, path) ->
                        open(path).also { require(it.resolved == path) }
                    }
                } else {
                    null
                }
                ImplementationArtifact.verifyDirect(implementation, root, entries)
                require(resources.allowsRead(root.resolved))
            }
            return Captured(resources, implementation, root, entries, frozen, strictCopy.isNotEmpty())
    }

    private fun open(path: Path): PreparedPath {
        val slot = synchronized(gate) {
            check(phase == Phase.RESOLVING && actor === Thread.currentThread())
            PreparedPath.DirectSlot(this, native, path).also { slots.add(it) }
        }
        return slot.open()
    }

    fun permits(
        slot: Any,
        source: NativeEngine,
        cleanup: Boolean,
    ): Boolean =
        synchronized(gate) {
        source === native &&
            slots.any { it === slot } &&
            actor === Thread.currentThread() &&
            phase == if (cleanup) Phase.RETIRING else Phase.RESOLVING
    }

    fun retirementSnapshot(): PreparedResolutionRetirement {
        var observedPhase = Phase.NEW
        var observedHistory: Throwable? = null
        var transferred = false
        synchronized(gate) {
            observedPhase = phase
            observedHistory = history
            transferred = claimedPermit != null
        }
        val saved = observedHistory
        return when {
            transferred -> PreparedResolutionRetirement.Transferred(saved)
            observedPhase == Phase.COMPLETE -> PreparedResolutionRetirement.Complete(saved)
            observedPhase == Phase.UNKNOWN -> PreparedResolutionRetirement.Unknown(checkNotNull(saved))
            else -> PreparedResolutionRetirement.Pending(saved)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A dispatched close with unknown outcome is retained and never replayed.
    fun retire(): Retirement {
        val immediate = reserveRetirement()
        if (immediate != null) return immediate
        try {
            for (slot in slots.asReversed()) slot.closeOwned()
        } catch (failure: Throwable) {
            val previous = history
            val fatal = recordRetireFailure(previous, failure)
            try {
                CleanupFailures.merge(previous, failure)
            } catch (bookkeepingFailure: Throwable) {
                throw fatal ?: bookkeepingFailure
            }
            if (fatal != null) throw fatal
            return Retirement.Unknown(checkNotNull(history))
        }
        return synchronized(gate) {
            actor = null
            phase = Phase.COMPLETE
            Retirement.Complete(history)
        }
    }

    private fun reserveRetirement(): Retirement? =
        synchronized(gate) {
            if (claimedPermit != null) return@synchronized Retirement.Transferred
            when (phase) {
                Phase.NEW -> {
                    phase = Phase.COMPLETE
                    Retirement.Complete(history)
                }
                Phase.RESOLVING, Phase.RETIRING -> Retirement.Pending(history)
                Phase.CLAIMED -> Retirement.Transferred
                Phase.COMPLETE -> Retirement.Complete(history)
                Phase.UNKNOWN -> Retirement.Unknown(checkNotNull(history))
                Phase.RETAINED -> {
                    if (validators != 0) {
                        retirementRequested = true
                        Retirement.Pending(history)
                    } else {
                        phase = Phase.RETIRING
                        actor = Thread.currentThread()
                        null
                    }
                }
            }
        }

    private fun recordRetireFailure(
        previous: Throwable?,
        failure: Throwable,
    ): Error? {
        val fatal = failure as? Error
        val primary = if (previous == null || fatal != null && previous !is Error) failure else previous
        var deliverFatal: Error? = null
        synchronized(gate) {
            history = primary
            retainedPreviousHistory = previous
            retainedCleanupFailure = failure
            if (fatal != null && fatal !== deliveredResolveError && fatal !== deliveredCleanupError) {
                deliveredCleanupError = fatal
                deliverFatal = fatal
            }
            actor = null
            phase = Phase.UNKNOWN
        }
        return deliverFatal
    }

    override fun close() {
        when (val result = retire()) {
            is Retirement.Complete -> Unit
            is Retirement.Pending -> throw DirectPendingException()
            is Retirement.Unknown -> throw DirectUnknownException(result.history)
            Retirement.Transferred -> Unit
        }
    }

    companion object {
        fun create() = DirectResolutionCapture()
    }
}
