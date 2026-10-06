package io.mazewall.enforcer.api

import io.mazewall.enforcer.engine.JvmFloorPresets
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall

/**
 * Mutable, fixed process-scope construction with immutable results.
 * Scope denotes applicability; required installation must also validate runtime compatibility.
 * Filesystem grants belong to [WorkerPolicyBuilder].
 */
public class ProcessPolicyBuilder private constructor(
    private val configuration: PolicyBuilder<PolicyScope.ProcessWideSafe>,
) {
    public fun defaultAction(action: SeccompAction): ProcessPolicyBuilder = apply { configuration.defaultAction(action) }

    public fun addAction(
        action: SeccompAction,
        vararg syscalls: Syscall,
    ): ProcessPolicyBuilder = apply { configuration.addAction(action, *syscalls) }

    public fun allow(vararg syscalls: Syscall): ProcessPolicyBuilder = apply { configuration.allow(*syscalls) }

    public fun block(vararg syscalls: Syscall): ProcessPolicyBuilder = apply { configuration.block(*syscalls) }

    /** Removes a configured action, never an installed restriction. */
    public fun unblock(vararg syscalls: Syscall): ProcessPolicyBuilder = apply { configuration.unblock(*syscalls) }

    /** Selects/merges a template; use policy intersection for restrictive composition. */
    public fun base(policy: Policy<PolicyScope.ProcessWideSafe, *>): ProcessPolicyBuilder = base(policy.definition)

    public fun base(definition: PolicyDefinition<PolicyScope.ProcessWideSafe>): ProcessPolicyBuilder = apply { configuration.base(definition) }

    public fun forRuntime(runtime: RuntimeProfile): ProcessPolicyBuilder = apply { configuration.forRuntime(runtime) }

    @JvmOverloads
    public fun allowMmapExec(allow: Boolean = true): ProcessPolicyBuilder = apply { configuration.setExecutableMappings(allow) }

    @JvmOverloads
    public fun allowNonThreadClone(allow: Boolean = true): ProcessPolicyBuilder = apply { configuration.setNonThreadClone(allow) }

    /** Unsafe pointer-bearing prctl options retain the documented sibling-thread TOCTOU limitation. */
    @JvmOverloads
    public fun allowUnsafePrctl(allow: Boolean = true): ProcessPolicyBuilder = apply { configuration.setUnsafePrctl(allow) }

    public fun lockIntelCet(): ProcessPolicyBuilder = apply { configuration.lockIntelCet() }

    public fun customViolationPhrase(phrase: String): ProcessPolicyBuilder = apply { configuration.customViolationPhrase(phrase) }

    public fun customViolationRegex(regex: Regex): ProcessPolicyBuilder = apply { configuration.customViolationRegex(regex) }

    public fun build(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy(configuration.build())

    public companion object {
        @JvmStatic
        public fun denyList(runtime: RuntimeProfile): ProcessPolicyBuilder = ProcessPolicyBuilder(PolicyBuilder<PolicyScope.ProcessWideSafe>().forRuntime(runtime))

        /** Includes the canonical JVM coordination/read floor; workload permissions remain explicit. */
        @JvmStatic
        public fun allowList(runtime: RuntimeProfile): ProcessPolicyBuilder =
            ProcessPolicyBuilder(
                PolicyBuilder<PolicyScope.ProcessWideSafe>()
                    .defaultAction(SeccompAction.ACT_ERRNO())
                    .forRuntime(runtime)
                    .apply { JvmFloorPresets.fullJvmFloor().forEach { allow(it) } },
            )
    }
}
