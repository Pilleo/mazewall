package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.util.TreeMap

/**
 * A declarative security policy definition.
 *
 * This class represents the immutable data model of security rules (syscalls, paths, flags)
 * as part of the decoupled Policy architecture. It adheres to the Single Responsibility Principle (SRP)
 * by focusing exclusively on rule representation and algebraic composition, delegating
 * construction to [PolicyBuilder] and compilation artifacts to [CompiledSandbox].
 */
public data class PolicyDefinition<out S : PolicyScope>(
    public val defaultAction: SeccompAction = SeccompAction.ACT_ALLOW,
    public val syscallActions: Map<Syscall, SeccompAction> = emptyMap(),
    public val allowMmapExec: Boolean = false,
    public val allowNonThreadClone: Boolean = false,
    public val allowUnsafePrctl: Boolean = false,
    public val allowedFsReadPaths: Set<SandboxedPath> = emptySet(),
    public val allowedFsWritePaths: Set<SandboxedPath> = emptySet(),
    internal val enforceLandlock: Boolean = false,
) {
    /** Returns true if the given [syscall] is unconditionally allowed by this policy. */
    public fun isSyscallAllowed(syscall: Syscall): Boolean {
        val action = syscallActions[syscall] ?: defaultAction
        return action == SeccompAction.ACT_ALLOW
    }

    /** Returns the concrete syscall numbers and their associated actions for the given [arch]. */
    public fun syscallActionNumbers(arch: Arch): Map<Int, SeccompAction> {
        val result = TreeMap<Int, SeccompAction>()
        for ((syscall, action) in syscallActions) {
            val nr = syscall.numberFor(arch)
            if (nr >= 0) {
                val current = result[nr]
                if (current == null || action.priority > current.priority) {
                    result[nr] = action
                }
            }
        }
        return result
    }

    public companion object {
        /**
         * Composes multiple policies into a single one.
         */
        public fun <S : PolicyScope> combine(vararg policies: PolicyDefinition<out S>): PolicyDefinition<S> {
            require(policies.isNotEmpty()) { "At least one policy is required" }

            val combinedDefaultAction = policies.maxByOrNull { it.defaultAction.priority }!!.defaultAction

            val combinedSyscalls = mutableMapOf<Syscall, SeccompAction>()
            for (policy in policies) {
                for ((syscall, action) in policy.syscallActions) {
                    val current = combinedSyscalls[syscall]
                    if (current == null || action.priority > current.priority) {
                        combinedSyscalls[syscall] = action
                    }
                }
            }

            val mmapExec = policies.all { it.allowMmapExec }
            val cloneNonThread = policies.all { it.allowNonThreadClone }
            val unsafePrctl = policies.all { it.allowUnsafePrctl }

            val allReadSets = policies.map { it.allowedFsReadPaths }.filter { it.isNotEmpty() }
            val fsReads = if (allReadSets.isEmpty()) emptySet() else allReadSets.reduce { acc, set -> intersectPaths(acc, set) }

            val allWriteSets = policies.map { it.allowedFsWritePaths }.filter { it.isNotEmpty() }
            val fsWrites = if (allWriteSets.isEmpty()) emptySet() else allWriteSets.reduce { acc, set -> intersectPaths(acc, set) }

            val enforceLandlock = policies.any { it.enforceLandlock }

            if (enforceLandlock) {
                combinedSyscalls[Syscall.OPEN] = SeccompAction.ACT_ALLOW
                combinedSyscalls[Syscall.OPENAT] = SeccompAction.ACT_ALLOW
                combinedSyscalls[Syscall.OPENAT2] = SeccompAction.ACT_ALLOW
            }

            @Suppress("UNCHECKED_CAST")
            return PolicyDefinition<S>(
                defaultAction = combinedDefaultAction,
                syscallActions = combinedSyscalls,
                allowMmapExec = mmapExec,
                allowNonThreadClone = cloneNonThread,
                allowUnsafePrctl = unsafePrctl,
                allowedFsReadPaths = fsReads,
                allowedFsWritePaths = fsWrites,
                enforceLandlock = enforceLandlock,
            )
        }

        private fun intersectPaths(
            set1: Set<SandboxedPath>,
            set2: Set<SandboxedPath>,
        ): Set<SandboxedPath> {
            if (set1.isEmpty() || set2.isEmpty()) return emptySet()

            val result = mutableSetOf<SandboxedPath>()
            val sortedSet2 = java.util.TreeSet(set2.map { it.value })

            for (p1 in set1) {
                val potentialParent = sortedSet2.floor(p1.value)
                if (potentialParent != null && isParent(potentialParent, p1.value)) {
                    result.add(p1)
                }

                val tail = sortedSet2.tailSet(p1.value, false)
                for (p2 in tail) {
                    if (isParent(p1.value, p2)) {
                        result.add(SandboxedPath.unsafe(p2))
                    } else {
                        break
                    }
                }
            }
            return result
        }

        private fun isParent(
            parent: String,
            child: String,
        ): Boolean {
            if (parent == child) return true
            val parentWithSlash = if (parent.endsWith("/")) parent else "$parent/"
            return child.startsWith(parentWithSlash)
        }
    }
}

/**
 * Compiles the high-level policy into kernel-ready BPF instructions for the given [arch].
 */
internal fun <S : PolicyScope> PolicyDefinition<S>.compile(arch: Arch): CompiledSandbox<S> {
    val filters = BpfFilter.build(arch, this)
    return CompiledSandbox(this, filters)
}
