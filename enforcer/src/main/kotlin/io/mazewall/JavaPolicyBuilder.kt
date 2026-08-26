package io.mazewall

import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.io.File
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Fluent builder designed for idiomatic Java usage to configure and build [Policy] instances.
 *
 * <h3>Ownership and Immutability</h3>
 * Each builder method returns the same [JavaPolicyBuilder] instance for method chaining, allowing
 * fluent configuration. However, filesystem rule methods (allowFsRead, allowFsWrite, allowFsReadWrite)
 * internally create new builder instances to handle scope promotion. Callers should use the returned
 * instance to preserve filesystem rules.
 *
 * <h3>Thread-Local vs Process-Wide</h3>
 * By default, this builder creates thread-local policies. To create a process-wide policy, use
 * [buildProcessWide]. Note that process-wide policies cannot have filesystem rules (Landlock)
 * as Landlock must be applied before seccomp, and seccomp process-wide installation would block the
 * Landlock system calls. Attempting to call [buildProcessWide] with filesystem rules will throw
 * an [IllegalStateException].
 *
 * <h3>Fail-Closed Defaults</h3>
 * The builder starts with a default action of [SeccompAction.ACT_ERRNO] (returning EPERM for blocked syscalls).
 * This can be changed using [defaultAction]. The fail-closed philosophy ensures that any syscall not
 * explicitly allowed will be blocked.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * Policy<PolicyScope.ThreadLocalOnly, Uncompiled> policy = Mazewall.threadLocalBuilder()
 *     .base(Mazewall.pureCompute())
 *     .allow(Syscall.READ, Syscall.WRITE)
 *     .allowFsRead("/tmp", "/var/tmp")
 *     .allowFsWrite("/tmp/scratch")
 *     .customViolationPhrase("Access Denied")
 *     .build();
 * }</pre>
 */
public class JavaPolicyBuilder(
    runtime: RuntimeProfile? = null,
) {
    // Mutable by necessity: fs-rule methods trigger scope PROMOTION in
    // PolicyBuilder (snapshotAsThreadLocal returns a NEW builder). Dropping the
    // returned instance silently lost every filesystem rule - and with it the
    // buildProcessWide() guard (Codex-adjacent find, PR #513 triage).
    private var builder: PolicyBuilder<PolicyScope.ThreadLocalOnly> = PolicyBuilder<PolicyScope.ThreadLocalOnly>()

    init {
        if (runtime != null) {
            builder.forRuntime(runtime)
        }
    }

    public fun defaultAction(action: SeccompAction): JavaPolicyBuilder {
        builder.defaultAction(action)
        return this
    }

    public fun allow(vararg syscalls: Syscall): JavaPolicyBuilder {
        builder.allow(*syscalls)
        return this
    }

    public fun block(vararg syscalls: Syscall): JavaPolicyBuilder {
        builder.block(*syscalls)
        return this
    }

    public fun unblock(vararg syscalls: Syscall): JavaPolicyBuilder {
        builder.unblock(*syscalls)
        return this
    }

    public fun addAction(action: SeccompAction, vararg syscalls: Syscall): JavaPolicyBuilder {
        builder.addAction(action, *syscalls)
        return this
    }

    public fun base(policy: Policy<*, *>): JavaPolicyBuilder {
        @Suppress("UNCHECKED_CAST")
        builder.base(policy.definition as PolicyDefinition<PolicyScope.ThreadLocalOnly>)
        return this
    }

    public fun allowFsRead(vararg paths: Path): JavaPolicyBuilder {
        for (path in paths) {
            builder = builder.allowFsRead(path.toAbsolutePath().normalize().toString())
        }
        return this
    }

    public fun allowFsRead(vararg files: File): JavaPolicyBuilder {
        for (file in files) {
            builder = builder.allowFsRead(file.absolutePath)
        }
        return this
    }

    public fun allowFsRead(vararg paths: String): JavaPolicyBuilder {
        for (path in paths) {
            builder = builder.allowFsRead(path)
        }
        return this
    }

    public fun allowFsWrite(vararg paths: Path): JavaPolicyBuilder {
        for (path in paths) {
            builder = builder.allowFsWrite(path.toAbsolutePath().normalize().toString())
        }
        return this
    }

    public fun allowFsWrite(vararg files: File): JavaPolicyBuilder {
        for (file in files) {
            builder = builder.allowFsWrite(file.absolutePath)
        }
        return this
    }

    public fun allowFsWrite(vararg paths: String): JavaPolicyBuilder {
        for (path in paths) {
            builder = builder.allowFsWrite(path)
        }
        return this
    }

    public fun allowFsReadWrite(vararg paths: Path): JavaPolicyBuilder {
        allowFsRead(*paths)
        allowFsWrite(*paths)
        return this
    }

    public fun allowFsReadWrite(vararg files: File): JavaPolicyBuilder {
        allowFsRead(*files)
        allowFsWrite(*files)
        return this
    }

    public fun allowFsReadWrite(vararg paths: String): JavaPolicyBuilder {
        allowFsRead(*paths)
        allowFsWrite(*paths)
        return this
    }

    public fun allowJvmClasspath(): JavaPolicyBuilder {
        builder = builder.allowJvmClasspath()
        return this
    }

    public fun allowMmapExec(allow: Boolean): JavaPolicyBuilder {
        if (allow) {
            builder.allowMmapExec()
        }
        return this
    }

    public fun allowMmapExec(): JavaPolicyBuilder {
        builder.allowMmapExec()
        return this
    }

    public fun forRuntime(runtime: RuntimeProfile): JavaPolicyBuilder {
        builder.forRuntime(runtime)
        return this
    }

    public fun allowNonThreadClone(): JavaPolicyBuilder {
        builder.allowNonThreadClone()
        return this
    }

    public fun allowUnsafePrctl(): JavaPolicyBuilder {
        builder.allowUnsafePrctl()
        return this
    }

    public fun lockIntelCet(): JavaPolicyBuilder {
        builder.lockIntelCet()
        return this
    }

    public fun customViolationPhrase(phrase: String): JavaPolicyBuilder {
        builder.customViolationPhrase(phrase)
        return this
    }

    public fun customViolationRegex(pattern: Pattern): JavaPolicyBuilder {
        builder.customViolationRegex(pattern.toRegex())
        return this
    }

    public fun customViolationRegex(regex: String): JavaPolicyBuilder {
        builder.customViolationRegex(regex.toRegex())
        return this
    }

    /**
     * Builds a thread-local policy from the current configuration.
     *
     * <p>The returned policy can be installed on individual threads using
     * [Mazewall.installOnCurrentThread].
     *
     * @return A thread-local policy ready for installation
     */
    public fun build(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        return Policy(builder.build())
    }

    /**
     * Builds a process-wide policy from the current configuration.
     *
     * <p><b>Critical:</b> Process-wide policies <b>cannot have filesystem rules (Landlock)</b>.
     * Landlock must be applied before seccomp, and a process-wide seccomp filter would block
     * the Landlock system calls. If this builder has any filesystem rules configured,
     * this method will throw an [IllegalStateException].
     *
     * <p>The returned policy can be installed process-wide using [Mazewall.installOnProcess].
     * Once installed, it affects all current and future threads in the process and cannot be removed.
     *
     * @return A process-wide policy ready for installation
     * @throws IllegalStateException if the builder has filesystem rules configured
     */
    public fun buildProcessWide(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> {
        val definition = builder.build()
        if (definition.enforceLandlock) {
            throw IllegalStateException(
                "Cannot create process-wide policy with Landlock filesystem rules. " +
                    "Filesystem rules must be ThreadLocalOnly.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return Policy(definition as PolicyDefinition<PolicyScope.ProcessWideSafe>)
    }

    public companion object {
        @JvmStatic
        public fun create(): JavaPolicyBuilder = JavaPolicyBuilder()

        @JvmStatic
        public fun create(runtime: RuntimeProfile): JavaPolicyBuilder = JavaPolicyBuilder(runtime)
    }
}
