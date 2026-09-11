package demo.vulnapp.config

import io.mazewall.profiler.EbpfLoad
import io.mazewall.profiler.ProfileEnvironment
import io.mazewall.profiler.ProfileStrategy
import io.mazewall.profiler.ProfilingCoverage
import io.mazewall.profiler.StackAttribution

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
