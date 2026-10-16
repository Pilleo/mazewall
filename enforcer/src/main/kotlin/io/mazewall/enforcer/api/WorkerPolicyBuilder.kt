package io.mazewall.enforcer.api

import io.mazewall.enforcer.engine.JvmFloorPresets
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import java.io.File
import java.nio.file.Path
import java.util.regex.Pattern

/** Mutable, fixed worker-scope construction. Every mutator retains this builder; build freezes its definition. */
public class WorkerPolicyBuilder private constructor(
    private var configuration: PolicyBuilder<PolicyScope.ThreadLocalOnly>,
) {
    public fun defaultAction(action: SeccompAction): WorkerPolicyBuilder = apply { configuration.defaultAction(action) }

    public fun addAction(
        action: SeccompAction,
        vararg syscalls: Syscall,
    ): WorkerPolicyBuilder = apply { configuration.addAction(action, *syscalls) }

    public fun allow(vararg syscalls: Syscall): WorkerPolicyBuilder = apply { configuration.allow(*syscalls) }

    public fun block(vararg syscalls: Syscall): WorkerPolicyBuilder = apply { configuration.block(*syscalls) }

    public fun unblock(vararg syscalls: Syscall): WorkerPolicyBuilder = apply { configuration.unblock(*syscalls) }

    /** Template composition can broaden grants. Use intersection to restrict an existing policy. */
    public fun base(policy: Policy<PolicyScope.ThreadLocalOnly, *>): WorkerPolicyBuilder = base(policy.definition)

    public fun base(definition: PolicyDefinition<PolicyScope.ThreadLocalOnly>): WorkerPolicyBuilder = apply { configuration.base(definition) }

    /** Assigns complete filesystem intent before installation, replacing previously configured grants. */
    public fun filesystem(policy: FileSystemPolicy): WorkerPolicyBuilder = apply { configuration.setFilesystem(policy) }

    public fun allowFsRead(vararg paths: String): WorkerPolicyBuilder =
        apply {
        paths.forEach { configuration = configuration.allowFsRead(it) }
    }

    public fun allowFsRead(path: SandboxedPath): WorkerPolicyBuilder = apply { configuration = configuration.allowFsRead(path) }

    public fun allowFsRead(vararg paths: Path): WorkerPolicyBuilder = apply { paths.forEach { allowFsRead(it.toString()) } }

    public fun allowFsRead(vararg files: File): WorkerPolicyBuilder = apply { files.forEach { allowFsRead(it.path) } }

    public fun allowFsWrite(vararg paths: String): WorkerPolicyBuilder =
        apply {
        paths.forEach { configuration = configuration.allowFsWrite(it) }
    }

    public fun allowFsWrite(path: SandboxedPath): WorkerPolicyBuilder = apply { configuration = configuration.allowFsWrite(path) }

    public fun allowFsWrite(vararg paths: Path): WorkerPolicyBuilder = apply { paths.forEach { allowFsWrite(it.toString()) } }

    public fun allowFsWrite(vararg files: File): WorkerPolicyBuilder = apply { files.forEach { allowFsWrite(it.path) } }

    public fun allowFsReadWrite(vararg paths: String): WorkerPolicyBuilder =
        apply {
        allowFsRead(*paths)
        allowFsWrite(*paths)
    }

    public fun allowFsReadWrite(vararg paths: Path): WorkerPolicyBuilder = apply { paths.forEach { allowFsReadWrite(it.toString()) } }

    public fun allowFsReadWrite(vararg files: File): WorkerPolicyBuilder = apply { files.forEach { allowFsReadWrite(it.path) } }

    public fun allowJvmClasspath(): WorkerPolicyBuilder = apply { configuration = configuration.allowJvmClasspath() }

    public fun forRuntime(runtime: RuntimeProfile): WorkerPolicyBuilder = apply { configuration.forRuntime(runtime) }

    @JvmOverloads
    public fun allowMmapExec(allow: Boolean = true): WorkerPolicyBuilder = apply { configuration.setExecutableMappings(allow) }

    @JvmOverloads
    public fun allowNonThreadClone(allow: Boolean = true): WorkerPolicyBuilder = apply { configuration.setNonThreadClone(allow) }

    /** Unsafe pointer-bearing prctl options retain the documented sibling-thread TOCTOU limitation. */
    @JvmOverloads
    public fun allowUnsafePrctl(allow: Boolean = true): WorkerPolicyBuilder = apply { configuration.setUnsafePrctl(allow) }

    public fun lockIntelCet(): WorkerPolicyBuilder = apply { configuration.lockIntelCet() }

    public fun customViolationPhrase(phrase: String): WorkerPolicyBuilder = apply { configuration.customViolationPhrase(phrase) }

    public fun customViolationRegex(regex: Regex): WorkerPolicyBuilder = apply { configuration.customViolationRegex(regex) }

    public fun customViolationRegex(regex: String): WorkerPolicyBuilder = customViolationRegex(Regex(regex))

    public fun customViolationRegex(pattern: Pattern): WorkerPolicyBuilder = customViolationRegex(pattern.toRegex())

    public fun build(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = Policy(configuration.build())

    public companion object {
        @JvmStatic
        public fun denyList(runtime: RuntimeProfile): WorkerPolicyBuilder = WorkerPolicyBuilder(PolicyBuilder<PolicyScope.ThreadLocalOnly>().forRuntime(runtime))

        @JvmStatic
        public fun allowList(runtime: RuntimeProfile): WorkerPolicyBuilder =
            WorkerPolicyBuilder(
                PolicyBuilder<PolicyScope.ThreadLocalOnly>()
                    .defaultAction(SeccompAction.ACT_ERRNO())
                    .forRuntime(runtime)
                    .apply { JvmFloorPresets.fullJvmFloor().forEach { allow(it) } },
            )
    }
}
