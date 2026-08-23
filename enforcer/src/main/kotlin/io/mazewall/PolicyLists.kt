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
 * stay on [PolicyBuilder] as the advanced definition-level API (see issue-20260823-171955
 * for the facade/engine layering decision).
 */
public object PolicyLists {
    @JvmStatic
    @JvmOverloads
    public fun denyList(
        runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
        configure: DenyListSpec.() -> Unit = {},
    ): Policy<out PolicyScope, Uncompiled> {
        val spec = DenyListSpec(runtime)
        spec.configure()
        return spec.build()
    }

    @JvmStatic
    @JvmOverloads
    public fun threadLocalDenyList(
        runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
        configure: DenyListSpec.() -> Unit = {},
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        val spec = DenyListSpec(runtime)
        spec.configure()
        @Suppress("UNCHECKED_CAST")
        return spec.build() as Policy<PolicyScope.ThreadLocalOnly, Uncompiled>
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
    private var inner: Policy.Builder<out PolicyScope> =
        Policy.builder().forRuntime(runtime)
    private var isThreadLocal: Boolean = false

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
        inner = inner.allowFsRead(path)
        isThreadLocal = true
        return this
    }

    public fun readOnly(path: SandboxedPath): DenyListSpec {
        inner = inner.allowFsRead(path)
        isThreadLocal = true
        return this
    }

    /** Escape hatch for raw syscall actions. */
    public fun advanced(block: Policy.Builder<PolicyScope.ProcessWideSafe>.() -> Unit): DenyListSpec {
        @Suppress("UNCHECKED_CAST")
        (inner as Policy.Builder<PolicyScope.ProcessWideSafe>).apply(block)
        return this
    }

    internal fun build(): Policy<out PolicyScope, Uncompiled> =
        if (isThreadLocal) {
            @Suppress("UNCHECKED_CAST")
            (inner as Policy.Builder<PolicyScope.ThreadLocalOnly>).build()
        } else {
            @Suppress("UNCHECKED_CAST")
            (inner as Policy.Builder<PolicyScope.ProcessWideSafe>).build()
        }
}

public class AllowListSpec internal constructor(
    runtime: RuntimeProfile,
) {
    private val inner: Policy.Builder<PolicyScope.ProcessWideSafe> =
        Policy
            .builder()
            .defaultAction(SeccompAction.ACT_ERRNO())
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
