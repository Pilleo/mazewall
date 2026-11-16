package io.mazewall.landlock

import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.execution.directRulesetStored
import io.mazewall.enforcer.api.execution.matchesDirectResources
import io.mazewall.enforcer.api.execution.matchesDirectShell
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.LandlockRulesetAttrSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.getFdOrThrow

/** One immutable ruleset and pinned object set, reused by every worker in a physical generation. */
internal class PreparedLandlock private constructor(
    private val resources: PreparedResourceSet,
    private var ruleset: LandlockRuleset<RulesetState.Building>?,
    private val borrowedEnvironment: InstallationEnvironment?,
    private val ownedEnvironment: InstallationEnvironment?,
    private val claimedOperation: io.mazewall.enforcer.api.execution.PreparedOperationResources?,
) : AutoCloseable {
    private enum class ResourceOwnership { Owned, Borrowed }

    private val ownership = if (borrowedEnvironment == null) ResourceOwnership.Owned else ResourceOwnership.Borrowed
    private constructor(resources: PreparedResourceSet, ruleset: LandlockRuleset<RulesetState.Building>?, environment: InstallationEnvironment?) : this(resources, ruleset, environment, null, null)

    private constructor(resources: PreparedResourceSet, ruleset: LandlockRuleset<RulesetState.Building>?) : this(resources, ruleset, null, null, null)

    private constructor(
        resources: PreparedResourceSet,
        environment: InstallationEnvironment,
        runtime: io.mazewall.enforcer.api.execution.ManagedRuntime,
        permit: Any,
        operation: io.mazewall.enforcer.api.execution.PreparedOperationResources,
    ) : this(resources, null, null, environment, operation) {
        check(runtime.matchesDirectResources(permit, operation, environment, resources))
        directRuntime = runtime
        directPermit = permit
    }

    private var acquiredRuleset:
        io.mazewall.platform.fd.FileDescriptor<io.mazewall.platform.fd.FileDescriptorRole.Ruleset, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>? = null

    private var directRuntime: io.mazewall.enforcer.api.execution.ManagedRuntime? = null
    private var directPermit: Any? = null
    private var directDispatched = false
    private var directPreparationReserved = false
    private var directAcquisitionSettled = false
    private var directCloseDispatched = false

    internal fun matchesRetained(
        resources: PreparedResourceSet,
        environment: InstallationEnvironment,
    ): Boolean = this.resources === resources && borrowedEnvironment === environment && acquiredRuleset == null && ruleset == null

    internal fun requireAcquisitionReady(environment: InstallationEnvironment) {
        check(directPermit == null) { "Direct shell requires its guarded operation" }
        check((borrowedEnvironment === environment || ownedEnvironment === environment) && acquiredRuleset == null && ruleset == null) { "Retained Landlock shell is not ready for acquisition" }
    }

    internal fun requireAcquisitionReady(): io.mazewall.platform.native.RuntimeSnapshot {
        val environment = checkNotNull(borrowedEnvironment ?: ownedEnvironment)
        requireAcquisitionReady(environment)
        return environment.native
    }

    private fun retainAcquiredRuleset(
        fd: io.mazewall.platform.fd.FileDescriptor<io.mazewall.platform.fd.FileDescriptorRole.Ruleset, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
    ) {
        acquiredRuleset = fd
    }

    @Synchronized
    internal fun closeOwnedRuleset() {
        check(directPermit == null) { "Direct shell requires guarded close" }
        check(ownership == ResourceOwnership.Owned)
        val environment = checkNotNull(ownedEnvironment)
        val fd = acquiredRuleset ?: return
        check(fd.isLiveForIo()) { "Owned acquisition ruleset has already retired" }
        environment.native.fileSystem
            .close(fd)
            .getOrThrow("close failed prepared ruleset")
        fd.markClosed()
    }

    internal fun mapRetainedAcquisition(
        result: io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled>,
        afterTypedStore: () -> Unit,
    ): LandlockFdOutcome {
        check(directPermit == null) { "Direct shell requires its guarded operation" }
        return when (result) {
            is io.mazewall.platform.native.LinuxNative.SyscallResult.Success -> {
                val fd = result.getFdOrThrow("create ruleset", io.mazewall.platform.fd.FileDescriptorRole.Ruleset)
                retainAcquiredRuleset(fd)
                afterTypedStore()
                LandlockFdOutcome.Ok(fd)
            }
            is io.mazewall.platform.native.LinuxNative.SyscallResult.Error -> LandlockFdOutcome.Err(result.errno, result.rawValue)
        }
    }

    internal fun prepareDirect() {
        val runtime = checkNotNull(directRuntime)
        val permit = checkNotNull(directPermit)
        val operation = checkNotNull(claimedOperation)
        check(runtime.matchesDirectShell(permit, operation, this, io.mazewall.enforcer.api.execution.DirectClaimAction.PREPARE_LANDLOCK))
        check(!directPreparationReserved && !directDispatched && acquiredRuleset == null && ruleset == null)
        directPreparationReserved = true
        val environment = checkNotNull(ownedEnvironment)
        val policy = operation.definition
        operation.requireDirectSource(runtime, permit, resources)
        resources.validate()
        val abi = environment.platform.capabilities.matrix.landlockAbiVersion
        check(abi >= 1)
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val attr = LandlockRulesetAttrSegment.allocate()
                attr.setHandledAccessFs(Landlock.getAccessMask(abi, policy, environment.fallback))
                attr.setHandledAccessNet(Landlock.getNetAccessMask(abi, policy))
                attr.setScoped(0L)
                check(runtime.matchesDirectShell(permit, operation, this@PreparedLandlock, io.mazewall.enforcer.api.execution.DirectClaimAction.PREPARE_LANDLOCK))
                directDispatched = true
                val result = environment.native.raw.syscall(
                    NativeConstants.LANDLOCK_CREATE_RULESET_NR,
                    io.mazewall.platform.syscall.NativeArg
                        .MemoryArg(attr.managed),
                    io.mazewall.platform.syscall.NativeArg
                        .LongArg(Landlock.rulesetAttrSize(abi)),
                    io.mazewall.platform.syscall.NativeArg
                        .MemoryArg(ManagedSegment.NULL),
                )
                when (result) {
                    is io.mazewall.platform.native.LinuxNative.SyscallResult.Success -> {
                        val fd = result.getFdOrThrow("create ruleset", io.mazewall.platform.fd.FileDescriptorRole.Ruleset)
                        acquiredRuleset = fd
                        directAcquisitionSettled = true
                        runtime.directRulesetStored(permit, operation, this@PreparedLandlock)
                        val building = LandlockRuleset<RulesetState.Building>(fd, environment.native)
                        ruleset = building
                        addDirectGrants()
                    }
                    is io.mazewall.platform.native.LinuxNative.SyscallResult.Error -> {
                        directAcquisitionSettled = true
                        error("Create prepared Landlock ruleset failed with errno=${result.errno}")
                    }
                }
            }
        }
        check(runtime.matchesDirectShell(permit, operation, this, io.mazewall.enforcer.api.execution.DirectClaimAction.PREPARE_LANDLOCK))
        resources.validate()
    }

    context(arena: NativeArena) private fun addDirectGrants() {
        val runtime = checkNotNull(directRuntime)
        val permit = checkNotNull(directPermit)
        val operation = checkNotNull(claimedOperation)
        val building = checkNotNull(ruleset)
        val abi = checkNotNull(ownedEnvironment)
            .platform.capabilities.matrix.landlockAbiVersion
        resources.grants.forEach { grant ->
            check(runtime.matchesDirectShell(permit, operation, this, io.mazewall.enforcer.api.execution.DirectClaimAction.PREPARE_LANDLOCK))
            when (val added = Landlock.addRuleToRuleset(building, grant.path.descriptor, grantAccess(grant, abi))) {
                is Landlock.AddRuleResult.Success -> Unit
                is Landlock.AddRuleResult.Error -> error("Add prepared Landlock resource ${grant.path.requested} failed with errno=${added.errno}")
            }
        }
    }

    fun validate() {
        val permit = directPermit
        if (permit == null) {
            synchronized(this) { resources.validate() }
        } else {
            val operation = checkNotNull(claimedOperation)
            check(checkNotNull(directRuntime).matchesDirectShell(permit, operation, this, io.mazewall.enforcer.api.execution.DirectClaimAction.PREPARE_LANDLOCK))
            operation.validate()
        }
    }

    fun apply(onRestrictionApplied: () -> Unit): LandlockApplyResult {
        directPermit?.let { permit ->
            check(checkNotNull(directRuntime).matchesDirectShell(permit, checkNotNull(claimedOperation), this, io.mazewall.enforcer.api.execution.DirectClaimAction.APPLY_WORKER))
        }
        return when (val result = Landlock.tryEnforceRuleset(checkNotNull(ruleset), onRestrictionApplied = onRestrictionApplied)) {
            is LandlockRestrictOutcome.Ok -> LandlockApplyResult.Applied
            is LandlockRestrictOutcome.Err -> LandlockApplyResult.Rejected("Prepared Landlock restriction rejected", errno = result.errno)
        }
    }

    internal fun closeDirect(action: io.mazewall.enforcer.api.execution.DirectClaimAction) {
        val runtime = checkNotNull(directRuntime)
        val permit = checkNotNull(directPermit)
        check(runtime.matchesDirectShell(permit, checkNotNull(claimedOperation), this, action))
        check(action == io.mazewall.enforcer.api.execution.DirectClaimAction.ROLLBACK_RULESET || action == io.mazewall.enforcer.api.execution.DirectClaimAction.RETIRE_DOMAIN)
        check(!directCloseDispatched)
        val fd = acquiredRuleset
        if (fd == null) {
            check(!directDispatched || directAcquisitionSettled) { "Direct ruleset acquisition outcome is unknown" }
            return
        }
        check(fd.isLiveForIo())
        directCloseDispatched = true
        checkNotNull(ownedEnvironment)
            .native.fileSystem
            .close(fd)
            .getOrThrow("close direct Landlock ruleset")
        fd.markClosed()
    }

    @Suppress("TooGenericExceptionCaught") // attempt every owned close; never mask cleanup failure
    @Synchronized
    override fun close() {
        check(directPermit == null) { "Direct shell requires guarded close" }
        when (ownership) {
            ResourceOwnership.Borrowed -> {
                val fd = acquiredRuleset ?: return
                check(fd.isLiveForIo()) { "Retained Landlock ruleset is not live for its first close" }
                checkNotNull(borrowedEnvironment)
                    .native.fileSystem
                    .close(fd)
                    .getOrThrow("close retained Landlock ruleset")
                fd.markClosed()
                return
            }
            ResourceOwnership.Owned -> Unit
        }
        val ruleset = checkNotNull(ruleset)
        var failure: Throwable? = null
        try {
            if (ruleset.fd.isLiveForIo()) {
                val result = ruleset.native.fileSystem.close(ruleset.fd)
                ruleset.fd.markClosed()
                result.getOrThrow("close prepared Landlock ruleset")
            }
        } catch (problem: Throwable) {
            failure = problem
        }
        if (claimedOperation == null) {
            try {
                resources.close()
            } catch (problem: Throwable) {
                failure = CleanupFailures.merge(failure, problem)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val REFER_ABI = 2
        private const val TRUNCATE_ABI = 3
        private val noAfterTypedStore: () -> Unit = {}

        internal fun allocateDirect(
            resources: PreparedResourceSet,
            environment: InstallationEnvironment,
            runtime: io.mazewall.enforcer.api.execution.ManagedRuntime,
            permit: Any,
            operation: io.mazewall.enforcer.api.execution.PreparedOperationResources,
        ): PreparedLandlock {
            return PreparedLandlock(resources, environment, runtime, permit, operation)
        }

        internal fun allocateRetained(
            resources: PreparedResourceSet,
            environment: InstallationEnvironment,
        ): PreparedLandlock = PreparedLandlock(resources, null, environment)

        internal fun allocateOwnedAcquisition(
            resources: PreparedResourceSet,
            environment: InstallationEnvironment,
        ): PreparedLandlock = PreparedLandlock(resources, null, null, environment, null)

        internal fun allocateOwnedAcquisition(
            operation: io.mazewall.enforcer.api.execution.PreparedOperationResources,
            environment: InstallationEnvironment,
        ): PreparedLandlock {
            check(operation.native.engineIdentity === environment.native.engineIdentity)
            return PreparedLandlock(operation.resources, null, null, environment, operation)
        }

        internal fun prepareOwnedAcquisitionWithPostStore(
            policy: PolicyDefinition<*>,
            environment: InstallationEnvironment,
            retained: PreparedLandlock,
            afterTypedStore: () -> Unit,
        ): PreparedLandlock? {
            check(retained.ownership == ResourceOwnership.Owned && retained.ownedEnvironment === environment)
            retained.requireAcquisitionReady(environment)
            val operation = retained.claimedOperation
            if (operation == null) {
                retained.resources.requireSource(policy, environment.native)
            } else {
                check(retained.resources === operation.resources && operation.native.engineIdentity === environment.native.engineIdentity)
                retained.resources.requireSource(policy, operation.native)
            }
            return prepareRetainedWithPostStore(policy, environment, retained, afterTypedStore)
        }

        internal fun prepareRetained(
            policy: PolicyDefinition<*>,
            environment: InstallationEnvironment,
            retained: PreparedLandlock,
        ): PreparedLandlock? = prepareRetainedWithPostStore(policy, environment, retained, noAfterTypedStore)

        internal fun prepareRetainedWithPostStore(
            policy: PolicyDefinition<*>,
            environment: InstallationEnvironment,
            retained: PreparedLandlock,
            afterTypedStore: () -> Unit,
        ): PreparedLandlock? {
            retained.requireAcquisitionReady(environment)
            if (!policy.enforceLandlock) return null
            retained.resources.validate()
            val abi = environment.platform.capabilities.matrix.landlockAbiVersion
            check(abi >= 1) { "Prepared filesystem containment requires Landlock" }
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val outcome = Landlock.tryCreateRulesetRetainedWithPostStore(
                        Landlock.getAccessMask(abi, policy, environment.fallback),
                        Landlock.getNetAccessMask(abi, policy),
                        abi,
                        retained,
                        afterTypedStore,
                    )
                    val fd = when (outcome) {
                        is LandlockFdOutcome.Ok -> outcome.fd
                        is LandlockFdOutcome.Err -> error("Create prepared Landlock ruleset failed with errno=${outcome.errno}")
                    }
                    val building = LandlockRuleset<RulesetState.Building>(fd, environment.native)
                    retained.ruleset = building
                    addGrants(building, retained.resources, abi)
                }
            }
            retained.validate()
            return retained
        }

        context(arena: NativeArena) private fun addGrants(
            ruleset: LandlockRuleset<RulesetState.Building>,
            resources: PreparedResourceSet,
            abi: Int,
        ) {
            resources.grants.forEach { grant ->
                when (val added = Landlock.addRuleToRuleset(ruleset, grant.path.descriptor, grantAccess(grant, abi))) {
                    is Landlock.AddRuleResult.Success -> Unit
                    is Landlock.AddRuleResult.Error -> error("Add prepared Landlock resource ${grant.path.requested} failed with errno=${added.errno}")
                }
            }
        }

        private fun grantAccess(
            grant: PreparedResourceSet.Grant,
            abi: Int,
        ): Long {
            val read = if (grant.read) Landlock.LANDLOCK_ACCESS_FS_READ_FILE or (if (grant.path.directory) Landlock.LANDLOCK_ACCESS_FS_READ_DIR else 0) else 0
            val write = if (grant.write) writeRights(abi, grant.path.directory) else 0
            return read or write or (if (grant.execute) Landlock.LANDLOCK_ACCESS_FS_EXECUTE else 0)
        }

        private fun writeRights(
            abi: Int,
            directory: Boolean,
        ): Long {
            val file = Landlock.LANDLOCK_ACCESS_FS_WRITE_FILE or (if (abi >= TRUNCATE_ABI) Landlock.LANDLOCK_ACCESS_FS_TRUNCATE else 0)
            return if (directory) {
                file or Landlock.LANDLOCK_ACCESS_FS_MAKE_REG or Landlock.LANDLOCK_ACCESS_FS_MAKE_DIR or
                Landlock.LANDLOCK_ACCESS_FS_REMOVE_FILE or Landlock.LANDLOCK_ACCESS_FS_REMOVE_DIR or
                (if (abi >= REFER_ABI) Landlock.LANDLOCK_ACCESS_FS_REFER else 0)
            } else {
                file
            }
        }
    }
}
