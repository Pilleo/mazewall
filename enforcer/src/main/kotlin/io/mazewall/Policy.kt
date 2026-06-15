package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.seccomp.BpfInstruction
import java.io.File
import java.nio.file.LinkOption
import java.nio.file.Paths

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
 * Policies are immutable and can be composed using [Policy.combine] or the `+` operator.
 * They use Phantom Types to track:
 * 1. **Scope ([S])**: Whether the policy is safe for process-wide application ([PolicyScope.ProcessWideSafe])
 *    or restricted to thread-local use ([PolicyScope.ThreadLocalOnly]).
 * 2. **State ([State])**: Whether the policy is [Uncompiled] (high-level DSL) or [Compiled] (contains BPF instructions).
 */
public class Policy<out S : PolicyScope, out State : PolicyState> private constructor(
    public val defaultAction: SeccompAction = SeccompAction.ACT_ALLOW,
    public val syscallActions: Map<Syscall, SeccompAction> = emptyMap(),
    public val allowMmapExec: Boolean = false,
    public val allowNonThreadClone: Boolean = false,
    public val allowUnsafePrctl: Boolean = false,
    public val allowedFsReadPaths: Set<SandboxedPath> = emptySet(),
    public val allowedFsWritePaths: Set<SandboxedPath> = emptySet(),
    internal val enforceLandlock: Boolean = false,
    private val compiledFiltersField: List<BpfInstruction>? = null,
) {
    public val compiledFilters: List<BpfInstruction>
        get() = compiledFiltersField ?: throw IllegalStateException("Policy is not compiled yet")

    /** Returns true if the given [syscall] is unconditionally allowed by this policy. */
    public fun isSyscallAllowed(syscall: Syscall): Boolean {
        val action = syscallActions[syscall] ?: defaultAction
        return action == SeccompAction.ACT_ALLOW
    }

    /** Returns the concrete syscall numbers and their associated actions for the given [arch]. */
    public fun syscallActionNumbers(arch: Arch): Map<Int, SeccompAction> {
        val result = java.util.TreeMap<Int, SeccompAction>()
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
        private val logger = java.util.logging.Logger
            .getLogger(Policy::class.java.name)

        /**
         * Low-level building block. Blocks all network I/O, process execution, and file opens.
         */
        @JvmField
        public val PURE_COMPUTE_UNSAFE: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            builder()
                .defaultAction(SeccompAction.ACT_ALLOW)
                .block(Syscall.CONNECT, Syscall.SENDTO, Syscall.SENDMSG, Syscall.SENDMMSG, Syscall.RECVMMSG, Syscall.SOCKET)
                .block(Syscall.BIND, Syscall.LISTEN, Syscall.ACCEPT, Syscall.ACCEPT4)
                .block(Syscall.EXECVE, Syscall.EXECVEAT)
                .block(Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2)
                .block(Syscall.RENAME, Syscall.RENAMEAT, Syscall.RENAMEAT2)
                .block(Syscall.LINK, Syscall.LINKAT, Syscall.UNLINK, Syscall.UNLINKAT)
                .block(Syscall.SYMLINK, Syscall.SYMLINKAT, Syscall.READLINK, Syscall.READLINKAT)
                .block(Syscall.MKDIR, Syscall.MKDIRAT, Syscall.RMDIR)
                .block(Syscall.CHMOD, Syscall.FCHMOD, Syscall.FCHMODAT)
                .build()

        /**
         * The absolute minimum baseline for any production JVM process.
         */
        @JvmField
        public val NO_EXEC: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            builder()
                .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
                .build()

        /**
         * Blocks all network-related system calls. Safe for process-wide application.
         */
        @JvmField
        public val NO_NETWORK: Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
            builder()
                .block(Syscall.CONNECT, Syscall.SENDTO, Syscall.SENDMSG, Syscall.SENDMMSG, Syscall.RECVMMSG, Syscall.SOCKET)
                .block(Syscall.BIND, Syscall.LISTEN, Syscall.ACCEPT, Syscall.ACCEPT4)
                .build()

        /**
         * Standard high-level preset for pure computational tasks.
         */
        @JvmField
        public val PURE_COMPUTE: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> =
            threadLocalBuilder()
                .base(PURE_COMPUTE_UNSAFE as Policy<PolicyScope.ThreadLocalOnly, Uncompiled>)
                .allowJvmClasspath()
                .build()

        @JvmStatic
        public fun builder(): Builder<PolicyScope.ProcessWideSafe> = Builder()

        /** Creates a builder specifically for thread-local policies (e.g. including FS rules). */
        @JvmStatic
        public fun threadLocalBuilder(): Builder<PolicyScope.ThreadLocalOnly> = Builder()

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

        @JvmStatic
        @JvmName("combineProcessWide")
        public fun combine(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>): Policy<PolicyScope.ProcessWideSafe, Uncompiled> {
            @Suppress("UNCHECKED_CAST")
            return combineInternal(*policies) as Policy<PolicyScope.ProcessWideSafe, Uncompiled>
        }

        @JvmStatic
        public fun combine(vararg policies: Policy<*, Uncompiled>): Policy<*, Uncompiled> {
            return combineInternal(*policies)
        }

        private fun combineInternal(vararg policies: Policy<*, Uncompiled>): Policy<*, Uncompiled> {
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

            return Policy<PolicyScope, Uncompiled>(
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

        internal fun <S : PolicyScope> compile(
            policy: Policy<S, Uncompiled>,
            filters: List<BpfInstruction>
        ): Policy<S, Compiled> = Policy<S, Compiled>(
            defaultAction = policy.defaultAction,
            syscallActions = policy.syscallActions,
            allowMmapExec = policy.allowMmapExec,
            allowNonThreadClone = policy.allowNonThreadClone,
            allowUnsafePrctl = policy.allowUnsafePrctl,
            allowedFsReadPaths = policy.allowedFsReadPaths,
            allowedFsWritePaths = policy.allowedFsWritePaths,
            enforceLandlock = policy.enforceLandlock,
            compiledFiltersField = filters
        )
    }

    public class Builder<S : PolicyScope> internal constructor(
        private var defaultAction: SeccompAction = SeccompAction.ACT_ALLOW,
        private val syscallActions: MutableMap<Syscall, SeccompAction> = mutableMapOf(),
        private var allowMmapExec: Boolean = false,
        private var allowNonThreadClone: Boolean = false,
        private var allowUnsafePrctl: Boolean = false,
        private val allowedFsReadPaths: MutableSet<SandboxedPath> = mutableSetOf(),
        private val allowedFsWritePaths: MutableSet<SandboxedPath> = mutableSetOf(),
    ) {
        public fun defaultAction(action: SeccompAction): Builder<S> {
            this.defaultAction = action
            return this
        }

        public fun addAction(action: SeccompAction, vararg syscalls: Syscall): Builder<S> {
            for (sys in syscalls) syscallActions[sys] = action
            return this
        }

        public fun block(vararg syscalls: Syscall): Builder<S> = addAction(SeccompAction.ACT_ERRNO, *syscalls)
        public fun allow(vararg syscalls: Syscall): Builder<S> = addAction(SeccompAction.ACT_ALLOW, *syscalls)

        public fun unblock(vararg syscalls: Syscall): Builder<S> {
            for (sys in syscalls) syscallActions.remove(sys)
            return this
        }

        public fun base(policy: Policy<out S, *>): Builder<S> {
            this.defaultAction = policy.defaultAction
            this.syscallActions.putAll(policy.syscallActions)
            if (policy.allowMmapExec) allowMmapExec = true
            if (policy.allowNonThreadClone) allowNonThreadClone = true
            if (policy.allowUnsafePrctl) allowUnsafePrctl = true
            allowedFsReadPaths.addAll(policy.allowedFsReadPaths)
            allowedFsWritePaths.addAll(policy.allowedFsWritePaths)
            return this
        }

        public fun allowFsRead(path: String): Builder<PolicyScope.ThreadLocalOnly> =
            allowFsRead(SandboxedPath.of(path))

        public fun allowFsRead(path: SandboxedPath): Builder<PolicyScope.ThreadLocalOnly> {
            allowedFsReadPaths.add(path)
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        public fun allowJvmClasspath(): Builder<PolicyScope.ThreadLocalOnly> {
            val javaHome = System.getProperty("java.home")
            if (!javaHome.isNullOrEmpty()) {
                allowFsRead(SandboxedPath.of(javaHome, allowNonExistent = true))
            }
            val classPath = System.getProperty("java.class.path")
            if (classPath != null) {
                addClasspathEntries(classPath)
            }
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        private fun addClasspathEntries(classPath: String) {
            classPath.split(File.pathSeparator).forEach { entry ->
                if (entry.isNotEmpty()) {
                    addClasspathFile(File(entry))
                }
            }
        }

        private fun addClasspathFile(file: File) {
            if (file.exists()) {
                val p = if (file.isDirectory) file.absolutePath else file.absoluteFile.parent
                if (p != null) {
                    allowFsRead(SandboxedPath.of(p, allowNonExistent = true))
                }
            }
        }

        public fun allowFsWrite(path: String): Builder<PolicyScope.ThreadLocalOnly> =
            allowFsWrite(SandboxedPath.of(path))

        public fun allowFsWrite(path: SandboxedPath): Builder<PolicyScope.ThreadLocalOnly> {
            allowedFsWritePaths.add(path)
            @Suppress("UNCHECKED_CAST")
            return this as Builder<PolicyScope.ThreadLocalOnly>
        }

        public fun allowMmapExec(): Builder<S> {
            this.allowMmapExec = true
            return this
        }

        public fun allowNonThreadClone(): Builder<S> {
            this.allowNonThreadClone = true
            return this
        }

        public fun allowUnsafePrctl(): Builder<S> {
            this.allowUnsafePrctl = true
            return this
        }

        public fun build(): Policy<S, Uncompiled> {
            val enforceLandlock = allowedFsReadPaths.isNotEmpty() || allowedFsWritePaths.isNotEmpty()
            val finalSyscalls = syscallActions.toMutableMap()
            if (enforceLandlock) {
                finalSyscalls[Syscall.OPEN] = SeccompAction.ACT_ALLOW
                finalSyscalls[Syscall.OPENAT] = SeccompAction.ACT_ALLOW
                finalSyscalls[Syscall.OPENAT2] = SeccompAction.ACT_ALLOW
            }
            return Policy<S, Uncompiled>(
                defaultAction = defaultAction,
                syscallActions = finalSyscalls,
                allowMmapExec = allowMmapExec,
                allowNonThreadClone = allowNonThreadClone,
                allowUnsafePrctl = allowUnsafePrctl,
                allowedFsReadPaths = allowedFsReadPaths.toSet(),
                allowedFsWritePaths = allowedFsWritePaths.toSet(),
                enforceLandlock = enforceLandlock,
            )
        }
    }
}

/**
 * Compiles the high-level policy into kernel-ready BPF instructions for the given [arch].
 */
internal fun <S : PolicyScope> Policy<S, Uncompiled>.compile(arch: Arch): Policy<S, Compiled> {
    val filters = BpfFilter.build(arch, this)
    return Policy.compile(this, filters)
}

/**
 * Composes two [PolicyScope.ProcessWideSafe] policies.
 * Resulting scope remains [PolicyScope.ProcessWideSafe].
 */
public operator fun Policy<PolicyScope.ProcessWideSafe, Uncompiled>.plus(
    other: Policy<PolicyScope.ProcessWideSafe, Uncompiled>,
): Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
    Policy.combine(this, other)

/**
 * Composes a policy with a [PolicyScope.ThreadLocalOnly] policy.
 * Resulting scope is downgraded to [PolicyScope.ThreadLocalOnly].
 */
@JvmName("plusThreadLocal")
public operator fun Policy<*, Uncompiled>.plus(
    other: Policy<PolicyScope.ThreadLocalOnly, Uncompiled>,
): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
    @Suppress("UNCHECKED_CAST")
    return Policy.combine(this, other) as Policy<PolicyScope.ThreadLocalOnly, Uncompiled>
}

/**
 * Composes a [PolicyScope.ThreadLocalOnly] policy with a [PolicyScope.ProcessWideSafe] policy.
 * Resulting scope is [PolicyScope.ThreadLocalOnly].
 */
@JvmName("plusThreadLocalReverse")
public operator fun Policy<PolicyScope.ThreadLocalOnly, Uncompiled>.plus(
    other: Policy<PolicyScope.ProcessWideSafe, Uncompiled>,
): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
    @Suppress("UNCHECKED_CAST")
    return Policy.combine(this, other) as Policy<PolicyScope.ThreadLocalOnly, Uncompiled>
}
