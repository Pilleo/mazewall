package io.mazewall

import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.io.File

/**
 * A builder for creating immutable [PolicyDefinition] instances.
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
    private val allowedFsReadPaths: MutableSet<SandboxedPath> = mutableSetOf(),
    private val allowedFsWritePaths: MutableSet<SandboxedPath> = mutableSetOf(),
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
        allowedFsReadPaths.addAll(policy.allowedFsReadPaths)
        allowedFsWritePaths.addAll(policy.allowedFsWritePaths)
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

    public fun allowMmapExec(): PolicyBuilder<S> {
        this.allowMmapExec = true
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
     * attacks (TOCTOU) by sibling threads. While register-based arguments (like args[0], the prctl option code)
     * are immune to TOCTOU, pointer-based arguments in options such as PR_SET_MM or PR_SET_NAME are subject
     * to TOCTOU. A sibling thread can modify the memory pointed to by the register argument concurrently
     * after the BPF filter's check but before kernel execution.
     */
    public fun allowUnsafePrctl(): PolicyBuilder<S> {
        this.allowUnsafePrctl = true
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
            allowedFsReadPaths = allowedFsReadPaths.toSet(),
            allowedFsWritePaths = allowedFsWritePaths.toSet(),
            enforceLandlock = enforceLandlock,
        )
    }
}
