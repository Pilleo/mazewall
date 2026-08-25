package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

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
    /**
     * Whether unsafe prctl options are allowed.
     *
     * WARNING: This option is extremely dangerous and inherently vulnerable to concurrent memory mutation
     * attacks (TOCTOU) by sibling threads. See [Policy.Builder.allowUnsafePrctl] for more details.
     */
    public val allowUnsafePrctl: Boolean = false,
    public val lockIntelCet: Boolean = false,
    public val allowedFsReadPaths: Set<SandboxedPath> = emptySet(),
    public val allowedFsWritePaths: Set<SandboxedPath> = emptySet(),
    internal val enforceLandlock: Boolean = false,
    public val customViolationPhrases: List<String> = emptyList(),
    public val customViolationRegexes: List<Regex> = emptyList()
) {
    public val hasSupervisedSyscalls: Boolean get() =
        syscallActions.values.any { it == SeccompAction.ACT_NOTIFY } ||
            defaultAction == SeccompAction.ACT_NOTIFY

    public val argumentRules: PolicyArgumentRules get() = PolicyArgumentRules.of(this)
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
                if (current == null) {
                    result[nr] = action
                } else {
                    result[nr] = current.stricter(action)
                }
            }
        }
        return result
    }

    public companion object {
        /**
         * Composes multiple policies into a single one.
         * Prefer [intersection] when the intent is "strictest wins".
         */
        public fun <S : PolicyScope> combine(vararg policies: PolicyDefinition<out S>): PolicyDefinition<S> {
            require(policies.isNotEmpty()) { "At least one policy is required" }

            val combinedDefaultAction =
                policies.map { it.defaultAction }.reduce { a, b -> a.stricter(b) }

            val combinedSyscalls = mutableMapOf<Syscall, SeccompAction>()
            val allSyscalls = policies.flatMapTo(mutableSetOf()) { it.syscallActions.keys }
            for (syscall in allSyscalls) {
                val effective =
                    policies
                        .map { it.syscallActions[syscall] ?: it.defaultAction }
                        .reduce { a, b -> a.stricter(b) }
                combinedSyscalls[syscall] = effective
            }

            val mmapExec = policies.all { it.allowMmapExec }
            val cloneNonThread = policies.all { it.allowNonThreadClone }
            val unsafePrctl = policies.all { it.allowUnsafePrctl }
            val lockCet = policies.any { it.lockIntelCet }

            val landlockPolicies = policies.filter { it.enforceLandlock }
            val fsReads =
                if (landlockPolicies.isEmpty()) {
                    emptySet()
                } else {
                    landlockPolicies.map { it.allowedFsReadPaths }.reduce { acc, set -> intersectPaths(acc, set) }
                }
            val fsWrites =
                if (landlockPolicies.isEmpty()) {
                    emptySet()
                } else {
                    landlockPolicies.map { it.allowedFsWritePaths }.reduce { acc, set -> intersectPaths(acc, set) }
                }

            val enforceLandlock = policies.any { it.enforceLandlock }

            val combinedPhrases = policies.flatMap { it.customViolationPhrases }.distinct()
            val combinedRegexes = policies.flatMap { it.customViolationRegexes }.distinct()

            if (enforceLandlock) {
                for (sys in listOf(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)) {
                    val current = combinedSyscalls[sys] ?: combinedDefaultAction
                    if (current.priority <= SeccompAction.ACT_ALLOW.priority) {
                        combinedSyscalls[sys] = SeccompAction.ACT_ALLOW
                    }
                }
            }
            val openAction = combinedSyscalls[Syscall.OPEN] ?: combinedDefaultAction
            val openatAction = combinedSyscalls[Syscall.OPENAT] ?: combinedDefaultAction
            val ioUringAction = combinedSyscalls[Syscall.IO_URING_SETUP] ?: combinedDefaultAction
            val openBlocked = openAction != SeccompAction.ACT_ALLOW
            val openatBlocked = openatAction != SeccompAction.ACT_ALLOW
            val ioUringAllowed = ioUringAction == SeccompAction.ACT_ALLOW
            if ((openBlocked || openatBlocked) && ioUringAllowed) {
                combinedSyscalls[Syscall.IO_URING_SETUP] = SeccompAction.ACT_ERRNO()
            }

            @Suppress("UNCHECKED_CAST")
            return PolicyDefinition<S>(
                defaultAction = combinedDefaultAction,
                syscallActions = combinedSyscalls,
                allowMmapExec = mmapExec,
                allowNonThreadClone = cloneNonThread,
                allowUnsafePrctl = unsafePrctl,
                lockIntelCet = lockCet,
                allowedFsReadPaths = fsReads,
                allowedFsWritePaths = fsWrites,
                enforceLandlock = enforceLandlock,
                customViolationPhrases = combinedPhrases,
                customViolationRegexes = combinedRegexes
            )
        }

        /** Same as [combine]; name states that the result is never more permissive. */
        public fun <S : PolicyScope> intersection(vararg policies: PolicyDefinition<out S>): PolicyDefinition<S> =
            combine(*policies)

        private fun intersectPaths(
            set1: Set<SandboxedPath>,
            set2: Set<SandboxedPath>,
        ): Set<SandboxedPath> {
            if (set1.isEmpty() || set2.isEmpty()) return emptySet()

            val result = mutableSetOf<SandboxedPath>()
            for (p1 in set1) {
                for (p2 in set2) {
                    if (isParent(p2.value, p1.value)) {
                        result.add(p1)
                    } else if (isParent(p1.value, p2.value)) {
                        result.add(p2)
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
