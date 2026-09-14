package demo.vulnapp.config

import io.mazewall.profiler.api.EbpfLoad
import io.mazewall.profiler.api.ProfileEnvironment
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.api.ProfilingCoverage
import io.mazewall.profiler.api.StackAttribution

internal fun collectorCoverage(droppedEvents: Int = 0): ProfilingCoverage =
    ProfilingCoverage.infer(
        strategy = ProfileStrategy.USER_NOTIF,
        strategyReason = "test collector",
        processWide = false,
        observations = emptyList(),
        stacks = StackAttribution.SKIPPED,
        droppedEvents = droppedEvents,
        drainComplete = true,
        environment = ProfileEnvironment("fixture", EbpfLoad.Denied("fixture")),
    )
