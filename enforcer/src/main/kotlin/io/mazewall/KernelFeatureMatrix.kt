package io.mazewall

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

/**
 * Immutable matrix of supported Linux kernel features.
 *
 * This object is constructed at startup and allows the library to safely
 * degrade or fail-fast based on actual kernel capabilities.
 *
 * @property seccompSupported True if the kernel supports Seccomp (mode 1/2).
 * @property seccompTsyncSupported True if SECCOMP_FILTER_FLAG_TSYNC is supported (Linux 3.17+).
 * @property seccompUserNotifSupported True if SECCOMP_FILTER_FLAG_NEW_LISTENER is supported (Linux 5.0+).
 * @property landlockAbiVersion The supported Landlock ABI version (0 = Unsupported, 1-8+).
 * @property cetSupported True if Intel CET Shadow Stack is supported (requires both CPU and kernel support).
 */
public data class KernelFeatureMatrix(
    val seccompSupported: Boolean,
    val seccompTsyncSupported: Boolean,
    val seccompUserNotifSupported: Boolean,
    val landlockAbiVersion: Int,
    val cetSupported: Boolean = false,
) {
    /** True if Landlock ABI v8 is available, supporting process-wide TSYNC. */
    val landlockTsyncSupported: Boolean get() = landlockAbiVersion >= 8

    /** True if any Landlock version is supported. */
    val landlockSupported: Boolean get() = landlockAbiVersion >= 1

    public companion object {
        /**
         * Resolves the [KernelFeatureMatrix] by probing the provided [provider].
         */
        internal fun resolve(provider: PlatformProvider): KernelFeatureMatrix {
            return KernelFeatureMatrix(
                seccompSupported = provider.hasKernelSeccompSupport(),
                seccompTsyncSupported = provider.probeSeccompTsync(),
                seccompUserNotifSupported = provider.probeSeccompUserNotif(),
                landlockAbiVersion = provider.getLandlockAbiVersion(),
                cetSupported = provider.probeCetSupported(),
            )
        }
    }
}
