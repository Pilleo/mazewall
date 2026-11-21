package io.mazewall.enforcer.api

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.state.*
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import java.util.TreeMap

/**
 * A declarative security policy definition.
 *
 * This class represents the immutable data model of security rules (syscalls, paths, flags)
 * as part of the decoupled Policy architecture. It adheres to the Single Responsibility Principle (SRP)
 * by focusing exclusively on rule representation and algebraic composition, delegating
 * construction to [PolicyBuilder] and compilation artifacts to [CompiledSandbox].
 */
public class PolicyDefinition<out S : PolicyScope>(
    public val defaultAction: SeccompAction = SeccompAction.ACT_ALLOW,
    syscallActions: Map<Syscall, SeccompAction> = emptyMap(),
    public val allowMmapExec: Boolean = false,
    public val allowNonThreadClone: Boolean = false,
    /**
     * Whether unsafe prctl options are allowed.
     *
     * WARNING: This option is extremely dangerous and inherently vulnerable to concurrent memory mutation
     * attacks (TOCTOU) by sibling threads. See [WorkerPolicyBuilder.allowUnsafePrctl] for more details.
     */
    public val allowUnsafePrctl: Boolean = false,
    public val lockIntelCet: Boolean = false,
    allowedFsReadPaths: Set<SandboxedPath> = emptySet(),
    allowedFsWritePaths: Set<SandboxedPath> = emptySet(),
    internal val enforceLandlock: Boolean = false,
    customViolationPhrases: List<String> = emptyList(),
    customViolationRegexes: List<Regex> = emptyList(),
) {
    public val syscallActions: Map<Syscall, SeccompAction> = java.util.Map.copyOf(syscallActions)
    public val allowedFsReadPaths: Set<SandboxedPath> = java.util.Set.copyOf(allowedFsReadPaths)
    public val allowedFsWritePaths: Set<SandboxedPath> = java.util.Set.copyOf(allowedFsWritePaths)
    public val customViolationPhrases: List<String> = java.util.List.copyOf(customViolationPhrases)
    public val customViolationRegexes: List<Regex> = java.util.List.copyOf(customViolationRegexes)

    public operator fun component1(): SeccompAction = defaultAction

    public operator fun component2(): Map<Syscall, SeccompAction> = syscallActions

    public operator fun component3(): Boolean = allowMmapExec

    public operator fun component4(): Boolean = allowNonThreadClone

    public operator fun component5(): Boolean = allowUnsafePrctl

    public operator fun component6(): Boolean = lockIntelCet

    public operator fun component7(): Set<SandboxedPath> = allowedFsReadPaths

    public operator fun component8(): Set<SandboxedPath> = allowedFsWritePaths

    internal operator fun component9(): Boolean = enforceLandlock

    public operator fun component10(): List<String> = customViolationPhrases

    public operator fun component11(): List<Regex> = customViolationRegexes

    public fun copy(
        defaultAction: SeccompAction = this.defaultAction,
        syscallActions: Map<Syscall, SeccompAction> = this.syscallActions,
        allowMmapExec: Boolean = this.allowMmapExec,
        allowNonThreadClone: Boolean = this.allowNonThreadClone,
        allowUnsafePrctl: Boolean = this.allowUnsafePrctl,
        lockIntelCet: Boolean = this.lockIntelCet,
        allowedFsReadPaths: Set<SandboxedPath> = this.allowedFsReadPaths,
        allowedFsWritePaths: Set<SandboxedPath> = this.allowedFsWritePaths,
        enforceLandlock: Boolean = this.enforceLandlock,
        customViolationPhrases: List<String> = this.customViolationPhrases,
        customViolationRegexes: List<Regex> = this.customViolationRegexes,
    ): PolicyDefinition<S> =
        PolicyDefinition(
            defaultAction = defaultAction,
            syscallActions = syscallActions,
            allowMmapExec = allowMmapExec,
            allowNonThreadClone = allowNonThreadClone,
            allowUnsafePrctl = allowUnsafePrctl,
            lockIntelCet = lockIntelCet,
            allowedFsReadPaths = allowedFsReadPaths,
            allowedFsWritePaths = allowedFsWritePaths,
            enforceLandlock = enforceLandlock,
            customViolationPhrases = customViolationPhrases,
            customViolationRegexes = customViolationRegexes,
        )

    override fun equals(other: Any?): Boolean = this === other || (other is PolicyDefinition<*> && hasSameScalarRules(other) && hasSameCollections(other))

    private fun hasSameScalarRules(other: PolicyDefinition<*>): Boolean =
        defaultAction == other.defaultAction &&
            allowMmapExec == other.allowMmapExec &&
            allowNonThreadClone == other.allowNonThreadClone &&
            allowUnsafePrctl == other.allowUnsafePrctl &&
            lockIntelCet == other.lockIntelCet &&
            enforceLandlock == other.enforceLandlock

    private fun hasSameCollections(other: PolicyDefinition<*>): Boolean =
        syscallActions == other.syscallActions &&
            allowedFsReadPaths == other.allowedFsReadPaths &&
            allowedFsWritePaths == other.allowedFsWritePaths &&
            customViolationPhrases == other.customViolationPhrases &&
            customViolationRegexes == other.customViolationRegexes

    override fun hashCode(): Int =
        java.util.Objects.hash(
            defaultAction,
            syscallActions,
            allowMmapExec,
            allowNonThreadClone,
            allowUnsafePrctl,
            lockIntelCet,
            allowedFsReadPaths,
            allowedFsWritePaths,
            enforceLandlock,
            customViolationPhrases,
            customViolationRegexes,
        )

    override fun toString(): String =
        "PolicyDefinition(defaultAction=$defaultAction, syscallActions=$syscallActions, " +
            "allowMmapExec=$allowMmapExec, allowNonThreadClone=$allowNonThreadClone, " +
            "allowUnsafePrctl=$allowUnsafePrctl, lockIntelCet=$lockIntelCet, " +
            "allowedFsReadPaths=$allowedFsReadPaths, allowedFsWritePaths=$allowedFsWritePaths, " +
            "enforceLandlock=$enforceLandlock, customViolationPhrases=$customViolationPhrases, " +
            "customViolationRegexes=$customViolationRegexes)"

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
        public fun <S : PolicyScope> combine(vararg policies: PolicyDefinition<out S>): PolicyDefinition<S> = combineAll(policies.asList())

        internal fun <S : PolicyScope> combineAll(policies: List<PolicyDefinition<S>>): PolicyDefinition<S> {
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
                    if (current.restrictivenessRank() <= SeccompAction.ACT_ALLOW.restrictivenessRank()) {
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
                customViolationRegexes = combinedRegexes,
            ).frozenCopy()
        }

        /** Same as [combine]; name states that the result is never more permissive. */
        public fun <S : PolicyScope> intersection(vararg policies: PolicyDefinition<out S>): PolicyDefinition<S> = combine(*policies)

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
 * Returns a defensively snapshotted copy of all collection properties (O(1) no-op for already-frozen
 * Java unmodifiable collections, while re-snapshotting if an internal collection field was replaced).
 */
internal fun <S : PolicyScope> PolicyDefinition<S>.frozenCopy(): PolicyDefinition<S> =
    copy(
        syscallActions = java.util.Map.copyOf(syscallActions),
        allowedFsReadPaths = java.util.Set.copyOf(allowedFsReadPaths),
        allowedFsWritePaths = java.util.Set.copyOf(allowedFsWritePaths),
        customViolationPhrases = java.util.List.copyOf(customViolationPhrases),
        customViolationRegexes = java.util.List.copyOf(customViolationRegexes),
    )

/**
 * Compiles the high-level policy into kernel-ready BPF instructions for the given [arch].
 */
internal fun <S : PolicyScope> PolicyDefinition<S>.compile(
    arch: Arch,
    warnOnUnsafePrctl: Boolean = true,
): CompiledSandbox<S> {
    val filters = BpfFilter.build(arch, this, warnOnUnsafePrctl = warnOnUnsafePrctl)
    return CompiledSandbox(this, filters)
}
