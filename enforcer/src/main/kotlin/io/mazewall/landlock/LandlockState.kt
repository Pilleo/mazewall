package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.FdState
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.nativeScope

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
        val rulesetFd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open>,
        val abi: Int,
    ) : LandlockState

    /** Enabling no_new_privs and restricting the thread. */
    data class Enforcing(
        val rulesetFd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open>,
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
    ) : LandlockState
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
    public val fd: FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open>
)

/**
 * Compiler-enforced type-state lifecycle for Landlock sandboxing.
 */
internal sealed interface LandlockLifecycle {
    /** Ruleset FD created, ready to add classpath and user rules. */
    class RulesetCreated(
        val ruleset: LandlockRuleset<RulesetState.Building>,
        val abi: Int,
        val policy: PolicyDefinition<*>?,
    ) : LandlockLifecycle {
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
        fun restrictSelf(processWide: Boolean = false): Restricted {
            Landlock.enforceRuleset(ruleset, processWide)
            return Restricted
        }
    }

    /** Ruleset successfully enforced and thread restricted. */
    data object Restricted : LandlockLifecycle
}

internal class LandlockSession(
    private val policy: PolicyDefinition<*>? = null,
    private val processWide: Boolean = false,
) {
    var state: LandlockState = LandlockState.Uninitialized
        private set

    @Suppress("TooGenericExceptionCaught")
    fun applyRuleset() {
        try {
            val features = Platform.featureMatrix
            val abi = features.landlockAbiVersion
            state = LandlockState.QueryingAbi(abi)

            if (processWide && !features.landlockTsyncSupported) {
                handleProcessWideUnsupported()
                // If we are warning and bypassing, we only continue if there are no rules.
                // But if there are rules, we continue and apply them to the current thread only.
                // Documentation states this is the accepted risk.
            }

            if (abi < 1) {
                if (policy != null) {
                    Landlock.handleUnsupportedLandlock()
                }
                state = LandlockState.Applied
                return
            }

            val accessMaskFs = if (policy != null) {
                Landlock.getAccessMask(abi, policy)
            } else {
                Landlock.getFullAccessMask(abi)
            }

            state = LandlockState.CreatingRuleset(abi)
            nativeScope {
                val rulesetFd = Landlock.createRuleset(accessMaskFs, abi)
                try {
                    val ruleset = LandlockRuleset<RulesetState.Building>(rulesetFd)
                    val created = LandlockLifecycle.RulesetCreated(ruleset, abi, policy)
                    state = LandlockState.ConfiguringRuleset(rulesetFd, abi)

                    val added = created.addRules(this)
                    state = LandlockState.Enforcing(rulesetFd)
                    added.restrictSelf(processWide)
                    state = LandlockState.Applied
                } finally {
                    LinuxNative.fileSystem.close(rulesetFd)
                }
            }
        } catch (t: Throwable) {
            state = LandlockState.Failed(t)
            throw t
        }
    }

    private fun handleProcessWideUnsupported() {
        val fallback = Platform.configuredFallback()
        val msg = "Process-wide Landlock (TSYNC) requires Linux 7.0+ (ABI v8). This kernel supports ABI v${Platform.featureMatrix.landlockAbiVersion}."
        if (fallback == Platform.FallbackBehavior.FAIL) {
            throw UnsupportedKernelFeatureException(msg)
        } else if (fallback == Platform.FallbackBehavior.WARN_AND_BYPASS) {
            java.util.logging.Logger.getLogger(Landlock::class.java.name).warning("$msg Rules will only be applied to the current thread and its descendants.")
        }
    }
}
