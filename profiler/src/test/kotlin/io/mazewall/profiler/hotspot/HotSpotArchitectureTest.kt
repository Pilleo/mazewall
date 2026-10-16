package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotSessionEffect
import io.mazewall.profiler.internal.hotspot.HotSpotSessionEvent
import io.mazewall.profiler.internal.hotspot.HotSpotSessionState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Profiler is deliberately outside enforcer ArchitectureTest's imported production modules. */
class HotSpotArchitectureTest {
    @Test
    fun sealedSecurityOutcomesHaveAClosedSubclassSet() {
        val expected = mapOf(
            HotSpotSessionState::class.java to setOf("Created", "Starting", "Ready", "Running", "Finishing", "Failed", "Completed"),
            HotSpotSessionEvent::class.java to
                setOf("Start", "Started", "Ready", "BeginWork", "Finish", "Summary", "ProcessExited", "StdoutEnded", "StderrJoined", "ResourcesJoined", "CleanupJoined", "Failure", "Tick"),
            HotSpotSessionEffect::class.java to setOf("Launch", "PublishReady", "RunWork", "RequestStop", "Abort", "AwaitOwner"),
        )
        for ((parent, variants) in expected) {
            assertEquals(variants, parent.permittedSubclasses.map { it.simpleName }.toSet(), "Update exhaustive lifecycle decisions before changing ${parent.name}")
        }
    }
}
