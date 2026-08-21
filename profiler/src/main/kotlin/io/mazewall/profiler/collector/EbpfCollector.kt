package io.mazewall.profiler.collector

import io.mazewall.profiler.EbpfLoad
import io.mazewall.profiler.IncompleteProfileException
import io.mazewall.profiler.IoUringVisibility
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileEnvironment
import io.mazewall.profiler.ProfileStrategy
import io.mazewall.profiler.ProfilingCoverage
import io.mazewall.profiler.StackAttribution
import java.nio.file.Files
import java.nio.file.Path

/**
 * eBPF collector.
 *
 * Live attach is not implemented (would need a privileged sidecar). A recorded
 * event log captured on a rootful host can be compiled anywhere.
 */
public class EbpfCollector(
    private val load: EbpfLoad,
    private val recordedLog: Path? = null,
    private val liveAttach: Boolean = false,
) : ProfileCollector {
    override val source: ObservationSource = ObservationSource.EBPF
    private var started = false

    override fun start() {
        if (liveAttach) {
            throw fail("live eBPF attach is not implemented; capture a recorded log on a rootful host")
        }
        if (recordedLog == null) {
            throw fail("no recorded eBPF event log; refuse to pretend io_uring was observed")
        }
        if (!Files.isRegularFile(recordedLog)) {
            throw fail("eBPF event log is not a file: $recordedLog")
        }
        started = true
    }

    override fun drain(): CollectorDrain {
        check(started) { "EbpfCollector.start() was not called" }
        val parsed = EbpfEventParser.parse(Files.readString(recordedLog!!))
        val ioUring = if (parsed.observations.any { it is io.mazewall.profiler.ProfileObservation.IoUring }) {
            IoUringVisibility.OBSERVED
        } else {
            IoUringVisibility.UNSEEN
        }
        return CollectorDrain(
            parsed.observations,
            droppedEvents = parsed.droppedLines,
            drainComplete = true,
            ioUring = ioUring,
        )
    }

    override fun close() {
        started = false
    }

    private fun fail(message: String): IncompleteProfileException {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = message,
            processWide = true,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = false,
            environment = ProfileEnvironment("unknown", load),
        )
        return IncompleteProfileException(coverage, message)
    }
}
