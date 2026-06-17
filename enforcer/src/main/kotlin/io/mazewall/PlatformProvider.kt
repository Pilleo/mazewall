package io.mazewall

/**
 * Interface for retrieving platform-specific diagnostics and capabilities.
 * Abstracting this allows for deterministic unit testing of fallback and diagnostic logic.
 */
public interface PlatformProvider {
    /** Returns the OS name (e.g., "Linux"). */
    fun getOsName(): String

    /** Returns the OS version. */
    fun getOsVersion(): String

    /** Returns the OS architecture (e.g., "amd64"). */
    fun getOsArch(): String

    /** Returns true if the kernel supports Seccomp (PR_GET_SECCOMP doesn't fail). */
    fun hasKernelSeccompSupport(): Boolean

    /** Returns the current Seccomp mode of the process. */
    fun getSeccompMode(): SeccompMode

    /**
     * Performs a bogus Seccomp call to verify that the kernel actively enforces filters.
     * Should return EINVAL (22) if healthy.
     */
    fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

    /** Returns true if the PR_SET_NO_NEW_PRIVS bit is set for the current process. */
    fun isNoNewPrivsEnabled(): Boolean

    /** Returns the current Yama ptrace_scope status. */
    fun getYamaPtraceScope(): YamaPtraceScope

    /** Returns the supported Landlock ABI version, or 0 if unsupported. */
    fun getLandlockAbiVersion(): Int

    /**
     * Probes for Seccomp TSYNC support (process-wide synchronization).
     * Should return true if the kernel recognizes SECCOMP_FILTER_FLAG_TSYNC.
     */
    fun probeSeccompTsync(): Boolean

    /**
     * Probes for Seccomp USER_NOTIF support (Tier S profiling).
     * Should return true if the kernel recognizes SECCOMP_FILTER_FLAG_NEW_LISTENER.
     */
    fun probeSeccompUserNotif(): Boolean

    /** Returns true if the process appears to be running inside a container (Docker/K8s/Podman). */
    fun isContainer(): Boolean
}
