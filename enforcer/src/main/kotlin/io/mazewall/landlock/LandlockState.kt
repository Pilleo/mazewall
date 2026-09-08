package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.close
import io.mazewall.core.use
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import io.mazewall.ffi.memory.NativeArena

/**
 * States representing the configuration and application of a Landlock ruleset.
 */
internal sealed interface LandlockState {
    /** The Landlock ruleset configuration session has not started. */
    data object Uninitialized : LandlockState

    /** Querying the kernel for the highest Landlock ABI version and determining policies. */
    data class QueryingAbi(
        val abi: Int,
    ) : LandlockState

    /** Creating the ruleset file descriptor via landlock_create_ruleset. */
    data class CreatingRuleset(
        val abi: Int,
    ) : LandlockState

    /** Ruleset FD created, adding classpath and user-defined path rules. */
    data class ConfiguringRuleset(
        val rulesetFd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open, FdOwnership.Owned>,
        val abi: Int,
    ) : LandlockState

    /** Enabling no_new_privs and restricting the thread. */
    data class Enforcing(
        val rulesetFd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open, FdOwnership.Owned>,
    ) : LandlockState

    /** Ruleset applied successfully to the thread. */
    data object Applied : LandlockState

    /**
     * Sandboxing session failed; stores the error that occurred.
     * Specifically handles all [Throwable] types to ensure native FFM and
     * unexpected JVM errors are strictly recorded.
     */
    data class Failed(
        val error: Throwable,
        /** Last successfully entered lifecycle phase before this failure. */
        val previous: LandlockState? = null,
    ) : LandlockState
}

/** A pure state transition produced by the Landlock installation evaluator. */
internal data class LandlockInstallTransition(
    val state: LandlockState,
    val effects: List<LandlockInstallEffect>,
)

/** Inputs returned to the installation evaluator by the session or native-effect interpreter. */
internal sealed interface LandlockInstallEvent {
    data class Begin(
        val abi: Int,
        val filesystemAccess: Long,
        val networkAccess: Long,
    ) : LandlockInstallEvent

    data class RulesetCreated(
        val ruleset: LandlockRuleset<RulesetState.Building>,
        val abi: Int,
        val policy: PolicyDefinition<*>?,
    ) : LandlockInstallEvent

    data class RulesAdded(
        val ruleset: LandlockRuleset<RulesetState.Building>,
        val processWide: Boolean,
    ) : LandlockInstallEvent

    data object RestrictionApplied : LandlockInstallEvent

    data object Bypassed : LandlockInstallEvent

    data class Failed(
        val error: Throwable,
    ) : LandlockInstallEvent
}

/** Native work requested by the installation evaluator. */
internal sealed interface LandlockInstallEffect {
    data class CreateRuleset(
        val filesystemAccess: Long,
        val networkAccess: Long,
        val abi: Int,
    ) : LandlockInstallEffect

    data class AddRules(
        val ruleset: LandlockRuleset<RulesetState.Building>,
        val abi: Int,
        val policy: PolicyDefinition<*>?,
    ) : LandlockInstallEffect

    data class RestrictSelf(
        val ruleset: LandlockRuleset<RulesetState.Building>,
        val processWide: Boolean,
    ) : LandlockInstallEffect

    data class CloseFd(
        val rulesetFd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open, FdOwnership.Owned>,
    ) : LandlockInstallEffect
}

/**
 * Pure Landlock installation state machine. Native calls are interpreted by
 * [LandlockSession], never performed while evaluating a transition.
 */
internal object LandlockInstall {
    fun evaluate(
        state: LandlockState,
        event: LandlockInstallEvent,
    ): LandlockInstallTransition =
        when (event) {
        is LandlockInstallEvent.Begin -> {
            require(state == LandlockState.Uninitialized) {
                "Landlock installation can only begin from Uninitialized"
            }
            LandlockInstallTransition(
                state = LandlockState.CreatingRuleset(event.abi),
                effects = listOf(
                    LandlockInstallEffect.CreateRuleset(
                        event.filesystemAccess,
                        event.networkAccess,
                        event.abi,
                    ),
                ),
            )
        }

        is LandlockInstallEvent.RulesetCreated -> {
            require(state == LandlockState.CreatingRuleset(event.abi)) {
                "Landlock ruleset creation must follow the matching creation phase"
            }
            LandlockInstallTransition(
                state = LandlockState.ConfiguringRuleset(event.ruleset.fd, event.abi),
                effects = listOf(
                    LandlockInstallEffect.AddRules(event.ruleset, event.abi, event.policy),
                ),
            )
        }

        is LandlockInstallEvent.RulesAdded -> {
            require(state is LandlockState.ConfiguringRuleset && state.rulesetFd == event.ruleset.fd) {
                "Landlock rules must be added from the matching configuration phase"
            }
            LandlockInstallTransition(
                state = LandlockState.Enforcing(event.ruleset.fd),
                effects = listOf(LandlockInstallEffect.RestrictSelf(event.ruleset, event.processWide)),
            )
        }

        LandlockInstallEvent.RestrictionApplied -> {
            require(state is LandlockState.Enforcing) {
                "Landlock restriction can only complete from Enforcing"
            }
            LandlockInstallTransition(
                LandlockState.Applied,
                listOf(LandlockInstallEffect.CloseFd(state.rulesetFd)),
            )
        }

        LandlockInstallEvent.Bypassed ->
            LandlockInstallTransition(LandlockState.Applied, emptyList())

        is LandlockInstallEvent.Failed ->
            LandlockInstallTransition(
                LandlockState.Failed(event.error, state),
                when (state) {
                    is LandlockState.ConfiguringRuleset -> listOf(LandlockInstallEffect.CloseFd(state.rulesetFd))
                    is LandlockState.Enforcing -> listOf(LandlockInstallEffect.CloseFd(state.rulesetFd))
                    LandlockState.Uninitialized,
                    is LandlockState.QueryingAbi,
                    is LandlockState.CreatingRuleset,
                    LandlockState.Applied,
                    is LandlockState.Failed,
                    -> emptyList()
                },
            )
    }
}

/**
 * Marker interfaces representing compile-time mutability states of a Landlock ruleset.
 */
public sealed interface RulesetState {
    public interface Building : RulesetState

    public interface Sealed : RulesetState
}

/**
 * A type-safe wrapper for a Landlock ruleset file descriptor, parameterized
 * by its mutability state [S] to prevent post-enforcement rule modifications.
 */
public class LandlockRuleset<out S : RulesetState> internal constructor(
    public val fd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open, FdOwnership.Owned>,
)

/**
 * Compiler-enforced type-state lifecycle for Landlock sandboxing.
 */
internal sealed interface LandlockLifecycle {
    val diagnosticState: LandlockState

    /** Ruleset FD created, ready to add classpath and user rules. */
    class RulesetCreated(
        val ruleset: LandlockRuleset<RulesetState.Building>,
        val abi: Int,
        val policy: PolicyDefinition<*>?,
    ) : LandlockLifecycle {
        override val diagnosticState: LandlockState = LandlockState.ConfiguringRuleset(ruleset.fd, abi)

        fun addRules(arena: NativeArena): RulesAdded {
            val allFsRead = Landlock.LANDLOCK_ACCESS_FS_READ_FILE or Landlock.LANDLOCK_ACCESS_FS_READ_DIR
            val classpathFlags = allFsRead or Landlock.LANDLOCK_ACCESS_FS_EXECUTE
            with(arena) {
                Landlock.addJvmClasspathRules(ruleset, classpathFlags)
                if (policy != null) {
                    Landlock.applyUserRules(ruleset, policy, abi, allFsRead)
                }
            }
            return RulesAdded(ruleset)
        }
    }

    /** Rules added, ready to restrict the thread. */
    class RulesAdded(
        val ruleset: LandlockRuleset<RulesetState.Building>,
    ) : LandlockLifecycle {
        override val diagnosticState: LandlockState = LandlockState.Enforcing(ruleset.fd)

        fun tryRestrictSelf(processWide: Boolean = false): LandlockRestrictOutcome = Landlock.tryEnforceRuleset(ruleset, processWide)

        fun restrictSelf(processWide: Boolean = false): Restricted {
            Landlock.enforceRuleset(ruleset, processWide)
            return Restricted
        }
    }

    /** Ruleset successfully enforced and thread restricted. */
    data object Restricted : LandlockLifecycle {
        override val diagnosticState: LandlockState = LandlockState.Applied
    }
}

internal class LandlockSession(
    private val policy: PolicyDefinition<*>? = null,
    private val processWide: Boolean = false,
) {
    var state: LandlockState = LandlockState.Uninitialized
        private set

    fun applyRuleset() {
        tryApplyRuleset().orThrow()
    }

    private fun transition(event: LandlockInstallEvent): List<LandlockInstallEffect> {
        val transition = LandlockInstall.evaluate(state, event)
        state = transition.state
        return transition.effects
    }

    private fun interpretTerminalEffects(effects: List<LandlockInstallEffect>) {
        effects.filterIsInstance<LandlockInstallEffect.CloseFd>().forEach { it.rulesetFd.close() }
    }

    fun tryApplyRuleset(): LandlockApplyResult {
        try {
            val features = Platform.featureMatrix
            val abi = features.landlockAbiVersion
            if (processWide && !features.landlockTsyncSupported) {
                handleProcessWideUnsupported()
            }
            return if (abi < 1) applyUnsupportedAbi() else applySupportedRuleset(abi)
        } catch (expectedInstallFailure: Exception) {
            return rejectInstallFailure(expectedInstallFailure)
        } catch (expectedFatalFailure: Error) {
            return rejectInstallFailure(expectedFatalFailure)
        }
    }

    private fun rejectInstallFailure(failure: Throwable): LandlockApplyResult {
        interpretTerminalEffects(transition(LandlockInstallEvent.Failed(failure)))
        return LandlockApplyResult.Rejected(failure.message ?: failure.javaClass.name, cause = failure)
    }

    private fun applyUnsupportedAbi(): LandlockApplyResult {
        val outcome = Landlock.handleUnsupportedLandlockOutcome()
        val event = when (outcome) {
            is LandlockApplyResult.Rejected -> LandlockInstallEvent.Failed(UnsupportedKernelFeatureException(outcome.reason))
            is LandlockApplyResult.Applied,
            is LandlockApplyResult.Bypassed,
            -> LandlockInstallEvent.Bypassed
        }
        transition(event)
        return outcome
    }

    private fun applySupportedRuleset(abi: Int): LandlockApplyResult {
        val (filesystemAccess, networkAccess) = accessMasks(abi)
        val createRuleset = transition(LandlockInstallEvent.Begin(abi, filesystemAccess, networkAccess))
            .single() as LandlockInstallEffect.CreateRuleset
        NativeArena.ofConfined().use { arena ->
            val created = with(arena) {
                Landlock.tryCreateRuleset(createRuleset.filesystemAccess, createRuleset.networkAccess, createRuleset.abi)
            }
            return when (created) {
                is LandlockFdOutcome.Err -> failNativeStep("landlock_create_ruleset", created.errno)
                is LandlockFdOutcome.Ok -> created.fd.use { rulesetFd -> applyRulesAndRestrict(rulesetFd, abi, arena) }
            }
        }
    }

    private fun applyRulesAndRestrict(
        rulesetFd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open, FdOwnership.Owned>,
        abi: Int,
        arena: NativeArena,
    ): LandlockApplyResult {
        val ruleset = LandlockRuleset<RulesetState.Building>(rulesetFd)
        val addRules = transition(LandlockInstallEvent.RulesetCreated(ruleset, abi, policy))
            .single() as LandlockInstallEffect.AddRules
        val added = LandlockLifecycle.RulesetCreated(addRules.ruleset, addRules.abi, addRules.policy).addRules(arena)
        val restrictSelf = transition(LandlockInstallEvent.RulesAdded(added.ruleset, processWide))
            .single() as LandlockInstallEffect.RestrictSelf
        return when (val restricted = added.tryRestrictSelf(restrictSelf.processWide)) {
            is LandlockRestrictOutcome.Err -> failNativeStep("landlock_restrict_self", restricted.errno)
            is LandlockRestrictOutcome.Ok -> {
                interpretTerminalEffects(transition(LandlockInstallEvent.RestrictionApplied))
                LandlockApplyResult.Applied
            }
        }
    }

    private fun failNativeStep(
        operation: String,
        errno: Int,
    ): LandlockApplyResult {
        val outcome = Landlock.classifyLandlockErrno(operation, errno)
        interpretTerminalEffects(transition(LandlockInstallEvent.Failed(outcome.toFailure())))
        return outcome
    }

    private fun handleProcessWideUnsupported() {
        val fallback = Platform.configuredFallback()
        val msg = "Process-wide Landlock requires ABI v8 with LANDLOCK_RESTRICT_SELF_TSYNC. " +
            "This kernel supports ABI v${Platform.featureMatrix.landlockAbiVersion}."
        if (fallback == Platform.FallbackBehavior.FAIL) {
            throw UnsupportedKernelFeatureException(msg)
        } else if (fallback == Platform.FallbackBehavior.WARN_AND_BYPASS) {
            java.util.logging.Logger
                .getLogger(Landlock::class.java.name)
                .warning("$msg Rules will only be applied to the current thread and its descendants.")
        }
    }

    private fun accessMasks(abi: Int): Pair<Long, Long> =
        policy?.let {
            Landlock.getAccessMask(abi, it) to Landlock.getNetAccessMask(abi, it)
        } ?: (Landlock.getFullAccessMask(abi) to Landlock.getFullNetAccessMask(abi))
}
