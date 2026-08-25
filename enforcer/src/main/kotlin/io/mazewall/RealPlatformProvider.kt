package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.core.PrctlCommand
import io.mazewall.ffi.NativeConstants
import java.io.File

@Suppress("SwallowedException")
internal object RealPlatformProvider : PlatformProvider {
    @JvmField
    internal var yamaPath: String = "/proc/sys/kernel/yama/ptrace_scope"

    override fun getOsName(): String = System.getProperty("os.name") ?: "Unknown"
    override fun getOsVersion(): String = System.getProperty("os.version") ?: "Unknown"
    override fun getOsArch(): String = System.getProperty("os.arch") ?: "Unknown"

    override fun hasKernelSeccompSupport(): Boolean =
        if (!getOsName().equals("Linux", ignoreCase = true)) false
        else LinuxNative.process.prctl(PrctlCommand.GetSeccomp) is LinuxNative.SyscallResult.Success

    override fun getSeccompMode(): SeccompMode = try {
        if (!getOsName().equals("Linux", ignoreCase = true)) {
            SeccompMode.Disabled
        } else {
            val seccompVal = LinuxNative.process.prctl(PrctlCommand.GetSeccomp)
            when (seccompVal) {
                is LinuxNative.SyscallResult.Error -> SeccompMode.Error(seccompVal.errno)
                is LinuxNative.SyscallResult.Success -> {
                    when (seccompVal.value) {
                        0L -> SeccompMode.Disabled
                        1L -> SeccompMode.Strict
                        2L -> SeccompMode.Filter
                        else -> SeccompMode.Error(-1)
                    }
                }
            }
        }
    } catch (e: IllegalStateException) {
        SeccompMode.Error(-1)
    }

    override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        if (!getOsName().equals("Linux", ignoreCase = true)) LinuxNative.SyscallResult.Error(NativeConstants.ENOSYS, -1L)
        else LinuxNative.process.prctl(PrctlCommand.SetSeccomp(-1L))

    override fun isNoNewPrivsEnabled(): Boolean = try {
        if (!getOsName().equals("Linux", ignoreCase = true)) {
            false
        } else {
            val nnpVal = LinuxNative.process.prctl(PrctlCommand.GetNoNewPrivs)
            if (nnpVal is LinuxNative.SyscallResult.Success) {
                nnpVal.value == 1L
            } else {
                false
            }
        }
    } catch (e: IllegalStateException) {
        false
    }

    @Suppress("MagicNumber")
    override fun getYamaPtraceScope(): YamaPtraceScope {
        if (!getOsName().equals("Linux", ignoreCase = true)) return YamaPtraceScope.Unavailable
        val file = File(yamaPath)
        if (!file.exists()) return YamaPtraceScope.Unavailable
        return try {
            val content = file.readText().trim()
            val intVal = content.toIntOrNull() ?: return YamaPtraceScope.Unavailable
            when (intVal) {
                0 -> YamaPtraceScope.Classic
                1 -> YamaPtraceScope.Restricted
                2 -> YamaPtraceScope.AdminOnly
                3 -> YamaPtraceScope.Disabled
                else -> YamaPtraceScope.Unknown(intVal)
            }
        } catch (e: java.io.IOException) {
            YamaPtraceScope.Unavailable
        } catch (e: SecurityException) {
            YamaPtraceScope.Unavailable
        }
    }

    override fun getLandlockAbiVersion(): Int = try {
        if (!getOsName().equals("Linux", ignoreCase = true)) 0
        else io.mazewall.landlock.Landlock.getAbiVersion()
    } catch (e: UnsupportedOperationException) {
        0
    } catch (e: IllegalStateException) {
        0
    }

    override fun probeSeccompTsync(): Boolean = probeSeccompFlag(NativeConstants.SECCOMP_FILTER_FLAG_TSYNC.toLong())

    override fun probeSeccompUserNotif(): Boolean = probeSeccompFlag(NativeConstants.SECCOMP_FILTER_FLAG_NEW_LISTENER)

    override fun probeCetSupported(): Boolean {
        if (!getOsName().equals("Linux", ignoreCase = true)) {
            return Platform.isCpuCetSupportedOverride ?: false
        }
        // Check CPU flags via /proc/cpuinfo for CET Shadow Stack support (shstk)
        val cpuSupported = try {
            val file = java.io.File("/proc/cpuinfo")
            if (file.exists()) {
                file.useLines { lines ->
                    lines.any { line ->
                        line.startsWith("flags") && line.split("\\s+".toRegex()).any { it.equals("shstk", ignoreCase = true) }
                    }
                }
            } else {
                false
            }
        } catch (e: java.io.IOException) {
            false
        } catch (e: SecurityException) {
            false
        }

        // Check kernel support via arch_prctl
        // Also respect the override if set (for testing)
        return (cpuSupported && Platform.isKernelCetSupported()) ||
               (Platform.isCpuCetSupportedOverride ?: false)
    }

    /**
     * Probes for a seccomp flag by performing a dry-run call with a NULL pointer.
     * If the kernel recognizes the flag, it returns EFAULT (Bad Address) because it
     * tries to read the filter program. If it doesn't recognize the flag, it returns EINVAL.
     */
    private fun probeSeccompFlag(flag: Long): Boolean {
        if (!getOsName().equals("Linux", ignoreCase = true)) return false
        val arch = try {
            io.mazewall.core.Arch.current()
        } catch (e: UnsupportedOperationException) {
            return false
        }
        val res = LinuxNative.raw.syscall(
            arch.seccompSyscallNumber.toLong(),
            io.mazewall.core.NativeArg.LongArg(NativeConstants.SECCOMP_SET_MODE_FILTER.toLong()),
            io.mazewall.core.NativeArg.LongArg(flag),
            io.mazewall.core.NativeArg.NullArg, // Trigger EFAULT on valid flags
        )
        // EFAULT (14) means the kernel recognized the flag and tried to read the NULL program.
        return res is LinuxNative.SyscallResult.Error && res.errno == NativeConstants.EFAULT
    }

    override fun isContainer(): Boolean {
        var isContainer = File("/.dockerenv").exists() ||
            File("/run/secrets/kubernetes.io").exists()

        if (!isContainer) {
            try {
                val cgroup = File("/proc/1/cgroup")
                if (cgroup.exists()) {
                    val content = cgroup.readText()
                    isContainer = content.contains("docker") ||
                        content.contains("podman") ||
                        content.contains("kubepods") ||
                        content.contains("containerd")
                }
            } catch (e: java.io.IOException) {
                // Ignore
            } catch (e: SecurityException) {
                // Ignore
            }
        }
        return isContainer
    }
}
