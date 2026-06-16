package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import java.util.logging.Logger

/**
 * Platform checks and fallback configuration.
 */
public object Platform {
    private val logger = Logger.getLogger(Platform::class.java.name)

    @Volatile
    private var provider: PlatformProvider = RealPlatformProvider

    /**
     * Swaps the active platform provider. Used for testing and fault injection.
     */
    @Suppress("spotbugs:ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public fun setProvider(newProvider: PlatformProvider) {
        provider = newProvider
    }

    /**
     * Restores the default RealPlatformProvider.
     */
    @Suppress("spotbugs:ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public fun resetToDefault() {
        provider = RealPlatformProvider
    }

    /**
     * Enum defining how the library behaves when run on an unsupported platform (e.g. macOS or Windows).
     */
    public enum class FallbackBehavior {
        FAIL, // throw UnsupportedOperationException
        WARN_AND_BYPASS, // log warning, run task uncontained
        SILENT_BYPASS, // run task uncontained, no warning
    }

    private const val ERRNO_EINVAL = 22

    /** True if the current operating system is Linux. */
    public val isLinux: Boolean get() = provider.getOsName().equals("Linux", ignoreCase = true)

    /**
     * Returns true if the current platform supports seccomp filters.
     */
    public fun isSupported(): Boolean =
        isLinux &&
            provider.hasKernelSeccompSupport() &&
            isSeccompSanityCheckPassing() &&
            isArchitectureSupported()

    private fun isSeccompSanityCheckPassing(): Boolean {
        // Bogus Sanity Check: Ensure the kernel actively enforces seccomp.
        // We call prctl(PR_SET_SECCOMP) with an invalid mode (-1).
        // A healthy kernel should return -1 and set errno to EINVAL (22).
        // Some container environments or broken kernels might silently return 0 or a different error.
        val bogusCheck = provider.checkSeccompSanity()
        val passed = bogusCheck is LinuxNative.SyscallResult.Error && bogusCheck.errno == ERRNO_EINVAL
        if (!passed) {
            val (ret, errno) =
                when (bogusCheck) {
                    is LinuxNative.SyscallResult.Success -> bogusCheck.value to 0
                    is LinuxNative.SyscallResult.Error -> bogusCheck.rawValue to bogusCheck.errno
                }
            logger.warning(
                "Seccomp sanity check failed. The kernel returned unexpected results (ret=$ret, errno=$errno). Seccomp may be stubbed or broken in this environment.",
            )
        }
        return passed
    }

    internal fun isArchitectureSupported(): Boolean =
        try {
            Arch.current()
            true
        } catch (e: UnsupportedOperationException) {
            logger.warning("Architecture not supported: ${e.message}")
            false
        }

    /**
     * Resolves the configured fallback behavior based on system properties or environment variables.
     */
    public fun configuredFallback(): FallbackBehavior {
        val prop =
            System.getProperty("io.mazewall.fallback")
                ?: System.getenv("IO_MAZEWALL_FALLBACK")

        if (prop != null) {
            try {
                return FallbackBehavior.valueOf(prop.uppercase())
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid fallback behavior '$prop' (${e.message}), defaulting to FAIL")
            }
        }
        return FallbackBehavior.FAIL
    }

    /**
     * Data class containing in-app diagnostics.
     */
    public data class Diagnostics(
        val osName: String,
        val osVersion: String,
        val osArch: String,
        val isLinux: Boolean,
        val isArchitectureSupported: Boolean,
        val isNoNewPrivsEnabled: Boolean,
        val seccompMode: SeccompMode,
        val yamaPtraceScope: YamaPtraceScope,
        val landlockAbiVersion: Int,
        val isContainer: Boolean,
    ) {
        override fun toString(): String {
            return """
                === Mazewall Platform Diagnostics ===
                OS Name: $osName ($osVersion)
                Architecture: $osArch (Supported: $isArchitectureSupported)
                Is Linux: $isLinux
                no_new_privs Enabled: $isNoNewPrivsEnabled
                Seccomp Mode: ${
                when (seccompMode) {
                    is SeccompMode.Disabled -> "Disabled (0)"
                    is SeccompMode.Strict -> "Strict Mode (1)"
                    is SeccompMode.Filter -> "Filter Mode (2)"
                    is SeccompMode.Error -> "Error (${seccompMode.errno})"
                }
            }
                Yama ptrace_scope: ${
                when (yamaPtraceScope) {
                    is YamaPtraceScope.Classic -> "Classic (0)"
                    is YamaPtraceScope.Restricted -> "Restricted (1)"
                    is YamaPtraceScope.AdminOnly -> "AdminOnly (2)"
                    is YamaPtraceScope.Disabled -> "Disabled (3)"
                    is YamaPtraceScope.Unknown -> "Unknown (${yamaPtraceScope.rawValue})"
                    is YamaPtraceScope.Unavailable -> "Unavailable"
                }
            }
                Landlock ABI Version: ${if (landlockAbiVersion > 0) "$landlockAbiVersion" else "Unsupported ($landlockAbiVersion)"}
                Container Detected: $isContainer
                =====================================
            """.trimIndent()
        }
    }

    /**
     * Run diagnostics to check system capabilities and privilege/sandboxing status.
     */
    public fun diagnose(): Diagnostics {
        return Diagnostics(
            osName = provider.getOsName(),
            osVersion = provider.getOsVersion(),
            osArch = provider.getOsArch(),
            isLinux = isLinux,
            isArchitectureSupported = isArchitectureSupported(),
            isNoNewPrivsEnabled = provider.isNoNewPrivsEnabled(),
            seccompMode = provider.getSeccompMode(),
            yamaPtraceScope = provider.getYamaPtraceScope(),
            landlockAbiVersion = provider.getLandlockAbiVersion(),
            isContainer = provider.isContainer(),
        )
    }
}
