package io.mazewall.portal

import java.util.concurrent.ConcurrentHashMap

/** Tracks stateful groups that cannot safely continue after an unknown-completion failure. */
internal class PortalStatefulInvocationGate {
    private val unavailableGroups = ConcurrentHashMap.newKeySet<String>()

    fun <T> invoke(
        manifest: PortalServiceManifest,
        call: () -> T,
    ): T {
        if (manifest.instanceMode == PortalInstanceMode.STATEFUL_SINGLETON && manifest.isolationGroup in unavailableGroups) {
            throw PortalStateLostException(manifest.isolationGroup)
        }
        try {
            return call()
        } catch (failure: PortalCallException) {
            if (manifest.instanceMode == PortalInstanceMode.STATEFUL_SINGLETON &&
                failure.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION
            ) {
                unavailableGroups.add(manifest.isolationGroup)
            }
            throw failure
        }
    }

    fun health(manifest: PortalServiceManifest): PortalServiceHealth =
        PortalServiceHealth(
            isolationGroup = manifest.isolationGroup,
            instanceMode = manifest.instanceMode,
            state =
                if (manifest.instanceMode == PortalInstanceMode.STATEFUL_SINGLETON && manifest.isolationGroup in unavailableGroups) {
                    PortalServiceHealthState.STATE_LOST
                } else {
                    PortalServiceHealthState.AVAILABLE
                },
        )
}
