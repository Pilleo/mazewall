package io.mazewall.portal

import java.util.concurrent.ConcurrentHashMap

/** Tracks stateful groups that cannot safely continue after an unknown-completion failure. */
internal class PortalStatefulInvocationGate {
    private class Group {
        var phase = StatefulGroupPhase.AVAILABLE

        fun transition(event: StatefulGroupEvent) {
            phase = PortalStatefulAdmission.evaluate(phase, event)
        }
    }

    private val groups = ConcurrentHashMap<String, Group>()

    @Suppress("TooGenericExceptionCaught") // fatal invocation errors terminally lose state and retain original identity
    fun <T> invoke(
        manifest: PortalServiceManifest,
        call: () -> T,
    ): T {
        if (manifest.instanceMode == PortalInstanceMode.STATELESS) return call()
        return tracked(admit(manifest), call)
    }

    private fun admit(manifest: PortalServiceManifest): Group {
        val group = groups.computeIfAbsent(manifest.isolationGroup) { Group() }
        synchronized(group) {
            when (group.phase) {
                StatefulGroupPhase.STATE_LOST -> throw PortalStateLostException(manifest.isolationGroup)
                StatefulGroupPhase.ACTIVE -> throw PortalCallException("Stateful isolation group already has an active invocation", PortalInvocationEvidence.NOT_STARTED)
                StatefulGroupPhase.AVAILABLE -> group.transition(StatefulGroupEvent.ADMIT)
            }
        }
        return group
    }

    @Suppress("TooGenericExceptionCaught") // fatal call loses state and is rethrown with its original identity
    private fun <T> tracked(group: Group, call: () -> T): T {
        try {
            return call()
        } catch (failure: PortalCallException) {
            if (failure.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION) synchronized(group) { group.transition(StatefulGroupEvent.LOST) }
            throw failure
        } catch (fatal: Error) {
            synchronized(group) { group.transition(StatefulGroupEvent.LOST) }
            throw fatal
        } finally {
            synchronized(group) { group.transition(StatefulGroupEvent.COMPLETED) }
        }
    }

    fun health(manifest: PortalServiceManifest): PortalServiceHealth =
        PortalServiceHealth(
            isolationGroup = manifest.isolationGroup,
            instanceMode = manifest.instanceMode,
            state =
                if (manifest.instanceMode == PortalInstanceMode.STATEFUL_SINGLETON &&
                    groups[manifest.isolationGroup]?.let { group -> synchronized(group) { group.phase == StatefulGroupPhase.STATE_LOST } } == true
                ) {
                    PortalServiceHealthState.STATE_LOST
                } else {
                    PortalServiceHealthState.AVAILABLE
                },
        )
}
