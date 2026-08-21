package io.mazewall

import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall

/** Whether the policy is a deny-list (default allow) or allow-list (default errno). */
public enum class PolicyMode {
    DENY_LIST,
    ALLOW_LIST,
}

/**
 * Capability-named deny-list / allow-list entry points. Raw `block`/`allow`/`unblock`
 * stay on [Policy.Builder] as the advanced API.
 */
public object PolicyLists {
    @JvmStatic
    @JvmOverloads
    public fun denyList(
        runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
        configure: DenyListSpec.() -> Unit = {},
    ): Policy<PolicyScope.ProcessWideSafe, Uncompiled> {
        val spec = DenyListSpec(runtime)
        spec.configure()
        return spec.build()
    }

    @JvmStatic
    @JvmOverloads
    public fun allowList(
        runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
        configure: AllowListSpec.() -> Unit = {},
    ): Policy<PolicyScope.ProcessWideSafe, Uncompiled> {
        val spec = AllowListSpec(runtime)
        spec.configure()
        return spec.build()
    }
}

public class DenyListSpec internal constructor(
    runtime: RuntimeProfile,
) {
    private val inner: Policy.Builder<PolicyScope.ProcessWideSafe> =
        Policy.builder().forRuntime(runtime)

    public val mode: PolicyMode = PolicyMode.DENY_LIST

    public fun denyProcessCreation(): DenyListSpec {
        inner.block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
        return this
    }

    public fun denyNetwork(): DenyListSpec {
        inner.block(
            Syscall.CONNECT,
            Syscall.SENDTO,
            Syscall.SENDMSG,
            Syscall.SENDMMSG,
            Syscall.RECVMMSG,
            Syscall.SOCKET,
            Syscall.BIND,
            Syscall.LISTEN,
            Syscall.ACCEPT,
            Syscall.ACCEPT4,
        )
        return this
    }

    public fun readOnly(path: String): DenyListSpec {
        inner.allowFsRead(path)
        return this
    }

    public fun readOnly(path: SandboxedPath): DenyListSpec {
        inner.allowFsRead(path)
        return this
    }

    /** Escape hatch for raw syscall actions. */
    public fun advanced(block: Policy.Builder<PolicyScope.ProcessWideSafe>.() -> Unit): DenyListSpec {
        inner.apply(block)
        return this
    }

    internal fun build(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = inner.build()
}

public class AllowListSpec internal constructor(
    runtime: RuntimeProfile,
) {
    private val inner: Policy.Builder<PolicyScope.ProcessWideSafe> =
        Policy
            .builder()
            .defaultAction(SeccompAction.ACT_ERRNO)
            .forRuntime(runtime)

    public val mode: PolicyMode = PolicyMode.ALLOW_LIST

    public fun allow(vararg syscalls: Syscall): AllowListSpec {
        inner.allow(*syscalls)
        return this
    }

    public fun denyProcessCreation(): AllowListSpec {
        inner.block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
        return this
    }

    public fun denyNetwork(): AllowListSpec {
        inner.block(
            Syscall.CONNECT,
            Syscall.SENDTO,
            Syscall.SENDMSG,
            Syscall.SENDMMSG,
            Syscall.RECVMMSG,
            Syscall.SOCKET,
            Syscall.BIND,
            Syscall.LISTEN,
            Syscall.ACCEPT,
            Syscall.ACCEPT4,
        )
        return this
    }

    public fun advanced(block: Policy.Builder<PolicyScope.ProcessWideSafe>.() -> Unit): AllowListSpec {
        inner.apply(block)
        return this
    }

    internal fun build(): Policy<PolicyScope.ProcessWideSafe, Uncompiled> = inner.build()
}
