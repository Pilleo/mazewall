package io.mazewall

import io.mazewall.ffi.NativeConstants
import java.io.File

internal object RealPlatformProvider : PlatformProvider {
    private const val PR_GET_SECCOMP = 21
    private const val PR_SET_SECCOMP = 22
    private const val PR_GET_NO_NEW_PRIVS = 39

    @JvmField
    internal var yamaPath: String = "/proc/sys/kernel/yama/ptrace_scope"

    override fun getOsName(): String = System.getProperty("os.name") ?: "Unknown"
    override fun getOsVersion(): String = System.getProperty("os.version") ?: "Unknown"
    override fun getOsArch(): String = System.getProperty("os.arch") ?: "Unknown"

    override fun hasKernelSeccompSupport(): Boolean = LinuxNative.withTransaction {
        LinuxNative.process.prctl(
            PR_GET_SECCOMP,
            io.mazewall.core.NativeArg.LongArg(0L),
            io.mazewall.core.NativeArg.LongArg(0L),
            io.mazewall.core.NativeArg.LongArg(0L),
            io.mazewall.core.NativeArg.LongArg(0L),
        )
    } is LinuxNative.SyscallResult.Success

    override fun getSeccompMode(): SeccompMode = try {
        val seccompVal = LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                PR_GET_SECCOMP,
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
            )
        }
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
    } catch (ignored: Exception) {
        SeccompMode.Error(-1)
    }

    override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
        LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                PR_SET_SECCOMP,
                io.mazewall.core.NativeArg.LongArg(-1L),
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
            )
        }

    override fun isNoNewPrivsEnabled(): Boolean = try {
        val nnpVal = LinuxNative.withTransaction {
            LinuxNative.process.prctl(
                PR_GET_NO_NEW_PRIVS,
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
            )
        }
        if (nnpVal is LinuxNative.SyscallResult.Success) {
            nnpVal.value == 1L
        } else {
            false
        }
    } catch (ignored: Exception) {
        false
    }

    @Suppress("MagicNumber")
    override fun getYamaPtraceScope(): YamaPtraceScope {
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
        } catch (ignored: Exception) {
            YamaPtraceScope.Unavailable
        }
    }

    override fun getLandlockAbiVersion(): Int = try {
        io.mazewall.landlock.Landlock.getAbiVersion()
    } catch (ignored: Exception) {
        0
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
            } catch (ignored: Exception) {
            }
        }
        return isContainer
    }
}
