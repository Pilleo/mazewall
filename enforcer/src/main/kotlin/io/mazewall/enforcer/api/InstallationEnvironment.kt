package io.mazewall.enforcer.api

import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.RuntimeSnapshot
import io.mazewall.seccomp.InstallSelfVerifier

/** Immutable authority selection reusable by an explicitly owned prepared runtime. */
internal class InstallationEnvironment private constructor(
    val native: RuntimeSnapshot,
    val platform: CapturedPlatform,
    val fallback: Platform.FallbackBehavior,
    val selfVerificationEnabled: Boolean,
) {
    companion object {
        fun capture(fallback: Platform.FallbackBehavior = Platform.configuredFallback()): InstallationEnvironment {
            val native = LinuxNative.snapshot()
            return InstallationEnvironment(native, Platform.capture(native), fallback, InstallSelfVerifier.isEnabled(native))
        }
    }
}
