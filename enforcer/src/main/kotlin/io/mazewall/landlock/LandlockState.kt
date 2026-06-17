package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.PolicyDefinition
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.FdState
import java.lang.foreign.Arena

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

    /** Sandboxing session failed; stores the error that occurred. */
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
        fun addRules(arena: Arena): RulesAdded {
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
