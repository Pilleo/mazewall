package io.mazewall

/** Internal capability snapshot retaining details omitted by the compatibility matrix. */
internal data class KernelFeatureSnapshot(
    val matrix: KernelFeatureMatrix,
    val landlockCapability: KernelCapability<Int>,
) {
    companion object {
        fun resolve(provider: PlatformProvider): KernelFeatureSnapshot {
            val landlockCapability = provider.getLandlockCapability()
            val matrix =
                KernelFeatureMatrix(
                    seccompSupported = provider.hasKernelSeccompSupport(),
                    seccompTsyncSupported = provider.probeSeccompTsync(),
                    seccompUserNotifSupported = provider.probeSeccompUserNotif(),
                    landlockAbiVersion = landlockCapability.availableValueOrZero(),
                    cetSupported = provider.probeCetSupported(),
                )
            return KernelFeatureSnapshot(matrix, landlockCapability)
        }
    }
}
