package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.io.File

/**
 * A builder for creating immutable [PolicyDefinition] instances.
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
 * @param S The [PolicyScope] (ProcessWideSafe or ThreadLocalOnly).
 */
public class PolicyBuilder<S : PolicyScope> internal constructor(
    private var defaultAction: SeccompAction = SeccompAction.ACT_ALLOW,
    private val syscallActions: MutableMap<Syscall, SeccompAction> = mutableMapOf(),
    private var allowMmapExec: Boolean = false,
    private var allowNonThreadClone: Boolean = false,
    /**
     * WARNING: Extremely dangerous and inherently vulnerable to concurrent memory mutation attacks (TOCTOU)
     * by sibling threads. See [allowUnsafePrctl] for details.
     */
    private var allowUnsafePrctl: Boolean = false,
    private var lockIntelCet: Boolean = false,
    private val allowedFsReadPaths: MutableSet<SandboxedPath> = mutableSetOf(),
    private val allowedFsWritePaths: MutableSet<SandboxedPath> = mutableSetOf(),
    private val customViolationPhrases: MutableList<String> = mutableListOf(),
    private val customViolationRegexes: MutableList<Regex> = mutableListOf()
) {
    public fun defaultAction(action: SeccompAction): PolicyBuilder<S> {
        this.defaultAction = action
        return this
    }

    public fun addAction(action: SeccompAction, vararg syscalls: Syscall): PolicyBuilder<S> {
        for (sys in syscalls) syscallActions[sys] = action
        return this
    }

    public fun block(vararg syscalls: Syscall): PolicyBuilder<S> = addAction(SeccompAction.ACT_ERRNO, *syscalls)
    public fun allow(vararg syscalls: Syscall): PolicyBuilder<S> = addAction(SeccompAction.ACT_ALLOW, *syscalls)

    public fun unblock(vararg syscalls: Syscall): PolicyBuilder<S> {
        for (sys in syscalls) syscallActions.remove(sys)
        return this
    }

    public fun base(policy: PolicyDefinition<out S>): PolicyBuilder<S> {
        this.defaultAction = policy.defaultAction
        this.syscallActions.putAll(policy.syscallActions)
        if (policy.allowMmapExec) allowMmapExec = true
        if (policy.allowNonThreadClone) allowNonThreadClone = true
        if (policy.allowUnsafePrctl) allowUnsafePrctl = true
        if (policy.lockIntelCet) lockIntelCet = true
        allowedFsReadPaths.addAll(policy.allowedFsReadPaths)
        allowedFsWritePaths.addAll(policy.allowedFsWritePaths)
        customViolationPhrases.addAll(policy.customViolationPhrases)
        customViolationRegexes.addAll(policy.customViolationRegexes)
        return this
    }

    public fun allowFsRead(path: String): PolicyBuilder<PolicyScope.ThreadLocalOnly> =
        allowFsRead(SandboxedPath.of(path, allowNonExistent = true))

    public fun allowFsRead(path: SandboxedPath): PolicyBuilder<PolicyScope.ThreadLocalOnly> {
        allowedFsReadPaths.add(path)
        @Suppress("UNCHECKED_CAST")
        return this as PolicyBuilder<PolicyScope.ThreadLocalOnly>
    }

    public fun allowJvmClasspath(): PolicyBuilder<PolicyScope.ThreadLocalOnly> {
        val javaHome = System.getProperty("java.home")
        if (!javaHome.isNullOrEmpty()) {
            allowFsRead(SandboxedPath.of(javaHome, allowNonExistent = true))
        }
        val classPath = System.getProperty("java.class.path")
        if (classPath != null) {
            addClasspathEntries(classPath)
        }
        @Suppress("UNCHECKED_CAST")
        return this as PolicyBuilder<PolicyScope.ThreadLocalOnly>
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

    public fun allowFsWrite(path: String): PolicyBuilder<PolicyScope.ThreadLocalOnly> =
        allowFsWrite(SandboxedPath.of(path, allowNonExistent = true))

    public fun allowFsWrite(path: SandboxedPath): PolicyBuilder<PolicyScope.ThreadLocalOnly> {
        allowedFsWritePaths.add(path)
        @Suppress("UNCHECKED_CAST")
        return this as PolicyBuilder<PolicyScope.ThreadLocalOnly>
    }

    /**
     * Advanced compatibility switch. Prefer [forRuntime] so the JIT vs W^X
     * choice is named rather than a boolean.
     */
    public fun allowMmapExec(): PolicyBuilder<S> {
        this.allowMmapExec = true
        return this
    }

    public fun forRuntime(runtime: RuntimeProfile): PolicyBuilder<S> {
        if (runtime.allowsExecutableMappings) {
            this.allowMmapExec = true
        }
        return this
    }

    public fun allowNonThreadClone(): PolicyBuilder<S> {
        this.allowNonThreadClone = true
        return this
    }

    /**
     * Allows unsafe prctl operations.
     *
     * WARNING: This option is extremely dangerous and inherently vulnerable to concurrent memory mutation
     * attacks (TOCTOU) by sibling threads. See [allowUnsafePrctl] for details.
     */
    public fun allowUnsafePrctl(): PolicyBuilder<S> {
        this.allowUnsafePrctl = true
        return this
    }

    public fun lockIntelCet(): PolicyBuilder<S> {
        this.lockIntelCet = true
        return this
    }

    public fun customViolationPhrase(phrase: String): PolicyBuilder<S> {
        this.customViolationPhrases.add(phrase)
        return this
    }

    public fun customViolationRegex(regex: Regex): PolicyBuilder<S> {
        this.customViolationRegexes.add(regex)
        return this
    }

    public fun build(): PolicyDefinition<S> {
        val enforceLandlock = allowedFsReadPaths.isNotEmpty() || allowedFsWritePaths.isNotEmpty()
        val finalSyscalls = syscallActions.toMutableMap()
        if (enforceLandlock) {
            finalSyscalls[Syscall.OPEN] = SeccompAction.ACT_ALLOW
            finalSyscalls[Syscall.OPENAT] = SeccompAction.ACT_ALLOW
            finalSyscalls[Syscall.OPENAT2] = SeccompAction.ACT_ALLOW
        } else {
            val openAction = finalSyscalls[Syscall.OPEN] ?: defaultAction
            val openatAction = finalSyscalls[Syscall.OPENAT] ?: defaultAction
            val ioUringAction = finalSyscalls[Syscall.IO_URING_SETUP] ?: defaultAction

            val openBlocked = openAction != SeccompAction.ACT_ALLOW
            val openatBlocked = openatAction != SeccompAction.ACT_ALLOW
            val ioUringAllowed = ioUringAction == SeccompAction.ACT_ALLOW

            if ((openBlocked || openatBlocked) && ioUringAllowed) {
                finalSyscalls[Syscall.IO_URING_SETUP] = SeccompAction.ACT_ERRNO
            }
        }
        return PolicyDefinition<S>(
            defaultAction = defaultAction,
            syscallActions = finalSyscalls,
            allowMmapExec = allowMmapExec,
            allowNonThreadClone = allowNonThreadClone,
            allowUnsafePrctl = allowUnsafePrctl,
            lockIntelCet = lockIntelCet,
            allowedFsReadPaths = allowedFsReadPaths.toSet(),
            allowedFsWritePaths = allowedFsWritePaths.toSet(),
            enforceLandlock = enforceLandlock,
            customViolationPhrases = customViolationPhrases.toList(),
            customViolationRegexes = customViolationRegexes.toList()
        )
    }
}
