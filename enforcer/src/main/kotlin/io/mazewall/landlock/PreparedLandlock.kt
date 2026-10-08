package io.mazewall.landlock

import io.mazewall.enforcer.api.InstallationEnvironment
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.CleanupFailures

/** One immutable ruleset and pinned object set, reused by every worker in a physical generation. */
internal class PreparedLandlock private constructor(
    private val resources: PreparedResourceSet,
    private val ruleset: LandlockRuleset<RulesetState.Building>,
) : AutoCloseable {
    @Synchronized
    fun validate() = resources.validate()

    fun apply(onRestrictionApplied: () -> Unit): LandlockApplyResult =
        when (val result = Landlock.tryEnforceRuleset(ruleset, onRestrictionApplied = onRestrictionApplied)) {
            is LandlockRestrictOutcome.Ok -> LandlockApplyResult.Applied
            is LandlockRestrictOutcome.Err -> LandlockApplyResult.Rejected("Prepared Landlock restriction rejected", errno = result.errno)
        }

    @Suppress("TooGenericExceptionCaught") // attempt every owned close; never mask cleanup failure
    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        try {
            if (ruleset.fd.isLiveForIo()) {
                ruleset.native.fileSystem
                .close(ruleset.fd)
                .getOrThrow("close prepared Landlock ruleset")
            }
        } catch (problem: Throwable) {
            failure = problem
        } finally {
            ruleset.fd.markClosed()
        }
        try {
            resources.close()
        } catch (problem: Throwable) {
            failure = CleanupFailures.merge(failure, problem)
        }
        failure?.let { throw it }
    }

    companion object {
        private const val REFER_ABI = 2
        private const val TRUNCATE_ABI = 3

        @Suppress("TooGenericExceptionCaught") // rollback all acquisitions before any worker is constructed
        fun prepare(
            policy: PolicyDefinition<*>,
            environment: InstallationEnvironment,
            resolved: PreparedResourceSet? = null,
        ): PreparedLandlock? {
            if (!policy.enforceLandlock) return null
            val resources = resolved ?: PreparedResourceSet.capture(policy, environment.native)
            var ruleset: LandlockRuleset<RulesetState.Building>? = null
            try {
                resources.validate()
                val abi = environment.platform.capabilities.matrix.landlockAbiVersion
                check(abi >= 1) { "Prepared filesystem containment requires Landlock" }
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                        val building = createRuleset(policy, environment, abi)
                        ruleset = building
                        addGrants(building, resources, abi)
                    }
                }
                return PreparedLandlock(resources, checkNotNull(ruleset)).also { it.validate() }
            } catch (failure: Throwable) {
                var primary = failure
                ruleset?.let { owned ->
                    try {
                        owned.native.fileSystem
                            .close(owned.fd)
                            .getOrThrow("close failed prepared ruleset")
                    } catch (
                        cleanup: Throwable,
                    ) {
                        primary = CleanupFailures.merge(primary, cleanup)
                    } finally {
                        owned.fd.markClosed()
                    }
                }
                try {
                    resources.close()
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
                throw primary
            }
        }

        context(arena: NativeArena) private fun createRuleset(
            policy: PolicyDefinition<*>,
            environment: InstallationEnvironment,
            abi: Int,
        ): LandlockRuleset<RulesetState.Building> {
            val result = Landlock.tryCreateRuleset(Landlock.getAccessMask(abi, policy, environment.fallback), Landlock.getNetAccessMask(abi, policy), abi, environment.native)
            return when (result) {
                is LandlockFdOutcome.Ok -> LandlockRuleset(result.fd, environment.native)
                is LandlockFdOutcome.Err -> error("Create prepared Landlock ruleset failed with errno=${result.errno}")
            }
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
