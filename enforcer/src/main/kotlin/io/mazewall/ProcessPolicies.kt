package io.mazewall

import io.mazewall.core.Syscall

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
        Policy
            .builder()
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
            .forRuntime(runtime)
            .build()

    /**
     * Blocks connect/send/socket/listen/accept family.
     * Same runtime rule as [denyProcessCreation] for executable mappings.
     */
    @JvmStatic
    public fun denyNetwork(runtime: RuntimeProfile): Policy<PolicyScope.ProcessWideSafe, Uncompiled> =
        Policy
            .builder()
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
     * Typed [PolicyScope.ThreadLocalOnly] because [Policy.Builder.allowJvmClasspath] is not
     * process-wide-safe: without Landlock ABI v8 `LANDLOCK_RESTRICT_SELF_TSYNC`, existing
     * JVM helper threads are not retroactively restricted. Portal workers apply this on the
     * dispatch thread after Unix connect; later threads inherit the ruleset. Extra readable
     * paths beyond classpath/`java.home` are deferred.
     */
    @JvmStatic
    public fun workerFilesystem(runtime: RuntimeProfile): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> =
        Policy
            .threadLocalBuilder()
            .allowJvmClasspath()
            .forRuntime(runtime)
            .build()
}
