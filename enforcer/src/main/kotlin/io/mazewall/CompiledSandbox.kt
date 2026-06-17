package io.mazewall

import io.mazewall.seccomp.BpfInstruction

/**
 * A compiled security policy, ready to be installed in the kernel.
 *
 * It contains the original [PolicyDefinition] and the generated BPF artifacts.
 *
 * @param S The [PolicyScope] (ProcessWideSafe or ThreadLocalOnly).
 */
public data class CompiledSandbox<out S : PolicyScope>(
    public val definition: PolicyDefinition<S>,
    public val compiledFilters: List<BpfInstruction>
)
