package io.mazewall

import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import java.io.File
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Fluent builder designed for idiomatic Java usage to configure and build [Policy] instances.
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

    public fun build(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        return Policy(builder.build())
    }

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
