package io.mazewall
import io.mazewall.enforcer.api.ContainedExecutors

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*

import io.mazewall.core.Arch
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.seccomp.BpfInstruction
import io.mazewall.seccomp.DefaultSyscallInspectionPipeline
import io.mazewall.seccomp.SyscallInspectionPipeline

/**
 * Marker interfaces for policy scopes.
 *
 * Hierarchy: [ProcessWideSafe] ⊂ [ThreadLocalOnly].
 * Anything safe for the whole process is safe for a single thread, but not vice versa.
 */
public sealed interface PolicyScope {
    /** Enforces thread-local restrictions (e.g. Landlock FS rules). */
    public interface ThreadLocalOnly : PolicyScope

    /** Safe for process-wide or thread-scoped containment. */
    public interface ProcessWideSafe : ThreadLocalOnly
}

/**
 * Marker interfaces for policy states.
 */
public sealed interface PolicyState {
    public interface Uncompiled : PolicyState
    public interface Compiled : PolicyState
}

public typealias Uncompiled = PolicyState.Uncompiled
public typealias Compiled = PolicyState.Compiled

/**
 * A kernel-enforced security policy defining permitted system calls and filesystem paths.
 *
 * ### Signal Management & Signal Mask Inheritance Warning
 * Standard JVM thread management and modern asynchronous or virtual-thread (Loom) frameworks rely on POSIX
 * signals and signal mask manipulation for I/O interruption, thread parking, and proper thread lifecycle operations.
 * When a new thread is spawned, it inherits the signal mask of its parent. If a seccomp policy blocks or restricts
 * `rt_sigprocmask` or `rt_sigaction`, a newly spawned thread can become permanently trapped with blocked signals.
 * This may lead to unkillable threads, missed thread interruptions (e.g. `Thread.interrupt()` failing to wake up
 * blocked I/O), or JVM instability.
 * Therefore, policies should ideally allow `rt_sigprocmask` and `rt_sigaction` for standard JVM thread management.
 * Note that the compiler automatically whitelists these critical JVM system calls in `BpfFilter.getJvmCriticalNrs`
 * to protect against thread desynchronization and signal mask inheritance failures.
 *
 * @deprecated Use [PolicyDefinition], [PolicyBuilder], or [CompiledSandbox] instead.
 * This class is maintained for backward compatibility during architectural refactoring.
 */
public class Policy<out S : PolicyScope, out State : PolicyState> internal constructor(
    public val definition: PolicyDefinition<S>,
    private val compiledFiltersField: List<BpfInstruction>? = null,
) {
    public val defaultAction: SeccompAction get() = definition.defaultAction
    public val syscallActions: Map<Syscall, SeccompAction> get() = definition.syscallActions
    public val allowMmapExec: Boolean get() = definition.allowMmapExec
    public val allowNonThreadClone: Boolean get() = definition.allowNonThreadClone
    /** Inspectable argument-inspection flags (JIT vs W^X, clone, prctl). */
    public val argumentRules: PolicyArgumentRules get() = PolicyArgumentRules.of(definition)

    /**
     * [PolicyMode.DENY_LIST] when the default action is allow; [PolicyMode.ALLOW_LIST]
     * when the default is errno. Other default actions stay [PolicyMode.DENY_LIST]
     * (legacy kill/trace defaults).
     */
    public val mode: PolicyMode
        get() =
            if (defaultAction is SeccompAction.ACT_ERRNO) {
                PolicyMode.ALLOW_LIST
            } else {
                PolicyMode.DENY_LIST
            }

    /**
     * Whether unsafe prctl options are allowed.
     *
     * WARNING: This option is extremely dangerous and inherently vulnerable to concurrent memory mutation
     * attacks (TOCTOU) by sibling threads. See [Policy.Builder.allowUnsafePrctl] for more details.
     */
    public val allowUnsafePrctl: Boolean get() = definition.allowUnsafePrctl
    public val allowedFsReadPaths: Set<SandboxedPath> get() = definition.allowedFsReadPaths
    public val allowedFsWritePaths: Set<SandboxedPath> get() = definition.allowedFsWritePaths
    internal val enforceLandlock: Boolean get() = definition.enforceLandlock
    public val customViolationPhrases: List<String> get() = definition.customViolationPhrases
    public val customViolationRegexes: List<Regex> get() = definition.customViolationRegexes

    public val compiledFilters: List<BpfInstruction>
        get() = compiledFiltersField ?: throw IllegalStateException("Policy is not compiled yet")

    /** Returns true if the given [syscall] is unconditionally allowed by this policy. */
    public fun isSyscallAllowed(syscall: Syscall): Boolean = definition.isSyscallAllowed(syscall)

    /** Returns the concrete syscall numbers and their associated actions for the given [arch]. */
    public fun syscallActionNumbers(arch: Arch): Map<Int, SeccompAction> = definition.syscallActionNumbers(arch)

    /**
     * Returns the active syscall inspection pipeline for this policy.
     */
    internal fun getSyscallInspectionPipeline(): SyscallInspectionPipeline {
        return BpfFilter.getSyscallInspectionPipeline()
    }

    public companion object {
        /**
         * Low-level building block. Blocks all network I/O, process execution, and file opens.
         */
        @JvmField
        public val PURE_COMPUTE_UNSAFE: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            Policy(PolicyPresets.PURE_COMPUTE_UNSAFE)

        /**
         * Blocks process execution and memfd. **Not** the process-wide HotSpot recipe:
         * hidden `allowMmapExec = false` denies `PROT_EXEC` mappings and can crash JIT.
         * Use [NO_EXEC_HOTSPOT] with [io.mazewall.enforcer.api.ContainedExecutors.installOnProcess].
         */
        @JvmField
        public val NO_EXEC: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            Policy(PolicyPresets.NO_EXEC)

        /**
         * Process-wide Tier 1 on HotSpot: no `execve` / `execveat` / `memfd_create`,
         * but JIT may still create executable mappings.
         */
        @JvmField
        public val NO_EXEC_HOTSPOT: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            Policy(PolicyPresets.NO_EXEC_HOTSPOT)

        /**
         * Same syscall set as [NO_EXEC]: W^X / Native Image process-wide baseline.
         * Prefer [ProcessPolicies.denyProcessCreation] with [RuntimeProfile.NATIVE_IMAGE].
         */
        @JvmField
        public val NO_EXEC_NATIVE_IMAGE: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            Policy(PolicyPresets.NO_EXEC_NATIVE_IMAGE)

        /**
         * Blocks all network-related system calls. Safe for process-wide application.
         */
        @JvmField
        public val NO_NETWORK: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            Policy(PolicyPresets.NO_NETWORK)

        /**
         * Standard high-level preset for pure computational tasks.
         */
        @JvmField
        public val PURE_COMPUTE: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> =
            Policy(PolicyPresets.PURE_COMPUTE)

        @JvmStatic
        public fun builder(): Builder<PolicyScope.ProcessWideSafe> = Builder(PolicyBuilder())

        /** Creates a builder specifically for thread-local policies (e.g. including FS rules). */
        @JvmStatic
        public fun threadLocalBuilder(): Builder<PolicyScope.ThreadLocalOnly> = Builder(PolicyBuilder())

        @JvmStatic
        @JvmName("combineProcessWide")
        public fun combine(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): Policy<PolicyScope.ProcessWideSafe, Uncompiled> {
            val defs = policies.map { it.definition }.toTypedArray()
            return Policy(PolicyDefinition.combine(*defs))
        }

        /**
         * Named intersection of process-wide policies. Same algebra as [combine]:
         * higher-priority Seccomp actions win; Landlock paths shrink; flags AND.
         * Does not expand permissions already installed in the kernel.
         */
        @JvmStatic
        public fun <S : PolicyScope> restrictFurtherWith(
            vararg policies: Policy<out S, Uncompiled>,
        ): Policy<S, Uncompiled> {
            val defs = policies.map { it.definition }.toTypedArray()
            return Policy(PolicyDefinition.combine(*defs))
        }

        @JvmStatic
        @JvmOverloads
        public fun denyList(
            runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
            configure: DenyListSpec.() -> Unit = {},
        ): Policy<out PolicyScope, Uncompiled> = PolicyLists.denyList(runtime, configure)

        @JvmStatic
        @JvmOverloads
        public fun threadLocalDenyList(
            runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
            configure: DenyListSpec.() -> Unit = {},
        ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = PolicyLists.threadLocalDenyList(runtime, configure)

        @JvmStatic
        @JvmOverloads
        public fun allowList(
            runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
            configure: AllowListSpec.() -> Unit = {},
        ): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = PolicyLists.allowList(runtime, configure)

        @JvmStatic
        public fun combine(vararg policies: Policy<*, Uncompiled>): Policy<*, Uncompiled> {
            val defs = policies.map { it.definition }.toTypedArray()
            return Policy(PolicyDefinition.combine(*defs))
        }

        internal fun <S : PolicyScope> compile(
            policy: Policy<S, Uncompiled>,
            filters: List<BpfInstruction>
        ): Policy<S, Compiled> = Policy<S, Compiled>(
            definition = policy.definition,
            compiledFiltersField = filters
        )
    }

    /**
     * Legacy builder to maintain API compatibility.
     */
    public class Builder<S : PolicyScope> internal constructor(
        private val internalBuilder: PolicyBuilder<S>
    ) {
        public fun defaultAction(action: SeccompAction): Builder<S> {
            internalBuilder.defaultAction(action)
            return this
        }

        public fun addAction(action: SeccompAction, vararg syscalls: Syscall): Builder<S> {
            internalBuilder.addAction(action, *syscalls)
            return this
        }

        public fun block(vararg syscalls: Syscall): Builder<S> = addAction(SeccompAction.ACT_ERRNO(), *syscalls)
        public fun allow(vararg syscalls: Syscall): Builder<S> = addAction(SeccompAction.ACT_ALLOW, *syscalls)

        /**
         * Removes an explicit action from this uncompiled builder only.
         * Installed kernel filters are monotonic and cannot grow.
         */
        public fun unblock(vararg syscalls: Syscall): Builder<S> {
            internalBuilder.unblock(*syscalls)
            return this
        }

        public fun base(policy: Policy<out S, *>): Builder<S> {
            internalBuilder.base(policy.definition)
            return this
        }

        public fun allowFsRead(path: String): Builder<PolicyScope.ThreadLocalOnly> {
            internalBuilder.allowFsRead(path)
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        public fun allowFsRead(path: SandboxedPath): Builder<PolicyScope.ThreadLocalOnly> {
            internalBuilder.allowFsRead(path)
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        public fun allowJvmClasspath(): Builder<PolicyScope.ThreadLocalOnly> {
            internalBuilder.allowJvmClasspath()
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        public fun allowFsWrite(path: String): Builder<PolicyScope.ThreadLocalOnly> {
            internalBuilder.allowFsWrite(path)
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        public fun allowFsWrite(path: SandboxedPath): Builder<PolicyScope.ThreadLocalOnly> {
            internalBuilder.allowFsWrite(path)
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        /**
         * Advanced: allow `mmap`/`mprotect` `PROT_EXEC`. Prefer
         * [forRuntime] with [RuntimeProfile.HOTSPOT_JIT] on process baselines.
         */
        public fun allowMmapExec(): Builder<S> {
            internalBuilder.allowMmapExec()
            return this
        }

        /** Apply JIT vs W^X executable-mapping policy for [runtime]. */
        public fun forRuntime(runtime: RuntimeProfile): Builder<S> {
            internalBuilder.forRuntime(runtime)
            return this
        }

        public fun allowNonThreadClone(): Builder<S> {
            internalBuilder.allowNonThreadClone()
            return this
        }

        /**
         * Allows unsafe prctl operations.
         *
         * WARNING: This option is extremely dangerous and inherently vulnerable to concurrent memory mutation
         * attacks (TOCTOU) by sibling threads. While register-based arguments (like args[0], the prctl option code)
         * are immune to TOCTOU, pointer-based arguments in options such as PR_SET_MM or PR_SET_NAME are subject
         * to TOCTOU. A sibling thread can modify the memory pointed to by the register argument concurrently
         * after the BPF filter's check but before kernel execution.
         */
        public fun allowUnsafePrctl(): Builder<S> {
            internalBuilder.allowUnsafePrctl()
            return this
        }

        public fun lockIntelCet(): Builder<S> {
            internalBuilder.lockIntelCet()
            return this
        }

        public fun customViolationPhrase(phrase: String): Builder<S> {
            internalBuilder.customViolationPhrase(phrase)
            return this
        }

        public fun customViolationRegex(regex: Regex): Builder<S> {
            internalBuilder.customViolationRegex(regex)
            return this
        }

        public fun build(): Policy<S, Uncompiled> {
            return Policy(internalBuilder.build())
        }
    }
}

/**
 * Compiles the high-level policy into kernel-ready BPF instructions for the given [arch].
 */
internal fun <S : PolicyScope> Policy<S, Uncompiled>.compile(arch: Arch): Policy<S, Compiled> {
    val program = BpfFilter.build(arch, this.definition)
    return Policy.compile(this, program.instructions)
}

/**
 * Composes two [PolicyScope.ProcessWideSafe] policies (intersection / restrict-further).
 */
@JvmName("plusProcessWide")
public operator fun Policy<PolicyScope.ProcessWideSafe, Uncompiled>.plus(
    other: Policy<PolicyScope.ProcessWideSafe, Uncompiled>
): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy.restrictFurtherWith(this, other)

/** Restrictive composition; same as [Policy.restrictFurtherWith]. */
public fun <S : PolicyScope> Policy<S, Uncompiled>.restrictFurtherWith(
    other: Policy<out S, Uncompiled>,
): Policy<S, Uncompiled> = Policy.restrictFurtherWith(this, other)

/**
 * Composes a policy with a thread-local policy.
 */
@JvmName("plusThreadLocal")
public operator fun <S : PolicyScope> Policy<S, Uncompiled>.plus(
    other: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>
): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
    @Suppress("UNCHECKED_CAST")
    return Policy.combine(this, other) as Policy<PolicyScope.ThreadLocalOnly, Uncompiled>
}

/**
 * Installs this policy on the current thread.
 */
public fun <S : PolicyScope> Policy<S, Uncompiled>.install(): io.mazewall.InstallationReceipt {
    return io.mazewall.enforcer.api.ContainedExecutors.installOnCurrentThread(this.definition)
}
