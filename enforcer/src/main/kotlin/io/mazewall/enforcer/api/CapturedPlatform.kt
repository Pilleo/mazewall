package io.mazewall.enforcer.api

import io.mazewall.platform.syscall.Arch

/** One provider selection; real probes are bound to the installing native owner. */
internal class CapturedPlatform(
    private val provider: PlatformProvider,
) {
    val isLinux: Boolean by lazy { provider.getOsName().equals("Linux", ignoreCase = true) }
    val capabilities: KernelFeatureSnapshot by lazy { KernelFeatureSnapshot.resolve(provider) }
    val isCetEligible: Boolean get() = isLinux && Arch.current() == Arch.AMD64

    fun isSupported(): Boolean = isLinux && Platform.isSupported(provider)
}
