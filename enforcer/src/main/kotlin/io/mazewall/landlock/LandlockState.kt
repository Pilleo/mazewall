package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.Uncompiled
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
        val rulesetFd: LinuxNative.FileDescriptor,
        val abi: Int,
    ) : LandlockState

    /** Enabling no_new_privs and restricting the thread. */
    data class Enforcing(
        val rulesetFd: LinuxNative.FileDescriptor,
    ) : LandlockState

    /** Ruleset applied successfully to the thread. */
    data object Applied : LandlockState

    /** Sandboxing session failed; stores the error that occurred. */
    data class Failed(
        val error: Throwable,
    ) : LandlockState
}

/**
 * Compiler-enforced type-state lifecycle for Landlock sandboxing.
 */
internal sealed interface LandlockLifecycle {
    /** Ruleset FD created, ready to add classpath and user rules. */
    class RulesetCreated(
        val rulesetFd: LinuxNative.FileDescriptor,
        val abi: Int,
        val policy: Policy<*, Uncompiled>?,
    ) : LandlockLifecycle {
        fun addRules(arena: Arena): RulesAdded {
            val allFsRead = Landlock.LANDLOCK_ACCESS_FS_READ_FILE or Landlock.LANDLOCK_ACCESS_FS_READ_DIR
            val classpathFlags = allFsRead or Landlock.LANDLOCK_ACCESS_FS_EXECUTE
            with(arena) {
                Landlock.addJvmClasspathRules(rulesetFd, classpathFlags)
                if (policy != null) {
                    Landlock.applyUserRules(rulesetFd, policy, abi, allFsRead)
                }
            }
            return RulesAdded(rulesetFd)
        }
    }

    /** Rules added, ready to restrict the thread. */
    class RulesAdded(
        val rulesetFd: LinuxNative.FileDescriptor,
    ) : LandlockLifecycle {
        fun restrictSelf(): Restricted {
            Landlock.enforceRuleset(rulesetFd)
            return Restricted
        }
    }

    /** Ruleset successfully enforced and thread restricted. */
    data object Restricted : LandlockLifecycle
}

