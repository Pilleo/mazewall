package io.mazewall.enforcer.api

import io.mazewall.platform.syscall.Syscall

/**
 * Capability-named process-wide factories. Every call takes an explicit
 * [RuntimeProfile] so JIT vs W^X is not a hidden builder default.
 */
public object ProcessPolicies {
    /**
     * Blocks `execve`, `execveat`, and `memfd_create`.
     * [RuntimeProfile.HOTSPOT_JIT] keeps executable mappings; [RuntimeProfile.NATIVE_IMAGE] does not.
     */
    @JvmStatic
    public fun denyProcessCreation(runtime: RuntimeProfile): Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
        ProcessPolicyBuilder
            .denyList(runtime)
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
            .block(Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER)
            .forRuntime(runtime)
            .build()

    /**
     * Blocks connect/send/socket/listen/accept family.
     * Same runtime rule as [denyProcessCreation] for executable mappings.
     */
    @JvmStatic
    public fun denyNetwork(runtime: RuntimeProfile): Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
        ProcessPolicyBuilder
            .denyList(runtime)
            .block(
                Syscall.CONNECT,
                Syscall.SENDTO,
                Syscall.SENDMSG,
                Syscall.SENDMMSG,
                Syscall.RECVMMSG,
                Syscall.SOCKET,
            ).block(Syscall.BIND, Syscall.LISTEN, Syscall.ACCEPT, Syscall.ACCEPT4)
            .forRuntime(runtime)
            .build()

    /**
     * Landlock allowlist of `java.home` and the current JVM classpath entries only.
     *
     * Typed [PolicyScope.ThreadLocalOnly] because [PolicyBuilder.allowJvmClasspath] is not
     * process-wide-safe: without Landlock ABI v8 `LANDLOCK_RESTRICT_SELF_TSYNC`, existing
     * JVM helper threads are not retroactively restricted. Dedicated portal workers require
     * ABI v8 and use the internal dedicated-process installer before readiness. Extra readable
     * paths beyond classpath/`java.home` are deferred.
     */
    @JvmStatic
    public fun workerFilesystem(runtime: RuntimeProfile): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> =
        WorkerPolicyBuilder
            .denyList(runtime)
            .allowJvmClasspath()
            .forRuntime(runtime)
            .build()
}
