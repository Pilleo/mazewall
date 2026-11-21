package io.mazewall.seccomp

import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.PrctlCommand

/**
 * Dependency-free seccomp capability and OS/architecture probes over [NativeEngine].
 *
 * Isolates [PureJavaBpfEngine], [SeccompInstallationState], and [ProcessFilterInstaller] from
 * [io.mazewall.enforcer.api.Platform] and Landlock/CET capability snapshots.
 */
internal object SeccompPlatformProbe {
    internal fun isLinux(): Boolean = (System.getProperty("os.name") ?: "").equals("Linux", ignoreCase = true)

    internal fun validateLinux() {
        if (!isLinux()) {
            throw UnsupportedOperationException("Mazewall requires Linux for kernel-level containment.")
        }
    }

    internal fun isArchitectureSupported(): Boolean =
        try {
            Arch.current()
            true
        } catch (_: UnsupportedOperationException) {
            false
        }

    internal fun hasKernelSeccompSupport(native: NativeEngine): Boolean {
        val result = native.process.prctl(PrctlCommand.GetSeccomp)
        return result is LinuxNative.SyscallResult.Success ||
            (result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EACCES)
    }

    internal fun isSeccompSanityCheckPassing(native: NativeEngine): Boolean {
        val bogusCheck = native.process.prctl(PrctlCommand.SetSeccomp(mode = -1L))
        return bogusCheck is LinuxNative.SyscallResult.Error && bogusCheck.errno == NativeConstants.EINVAL
    }

    internal fun isSupported(native: NativeEngine = LinuxNative.snapshot()): Boolean =
        isLinux() &&
            hasKernelSeccompSupport(native) &&
            isSeccompSanityCheckPassing(native) &&
            isArchitectureSupported()

    internal fun isSeccompTsyncSupported(native: NativeEngine): Boolean {
        if (!isSupported(native)) return false
        val arch = Arch.current()
        val result =
            native.raw.syscall(
                arch.seccompSyscallNumber.toLong(),
                NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
                NativeArg.LongArg(NativeConstants.SECCOMP_FILTER_FLAG_TSYNC.toLong()),
                NativeArg.NullArg,
            )
        return result is LinuxNative.SyscallResult.Error && result.errno == NativeConstants.EFAULT
    }
}
