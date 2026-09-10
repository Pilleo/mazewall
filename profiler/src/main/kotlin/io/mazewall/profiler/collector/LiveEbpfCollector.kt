package io.mazewall.profiler.collector

import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.engine.ObservedSyscall

/** Converts live Tier E records into strategy-neutral profile observations. */
public class ProfileObservationEbpfCollector(
    private val arch: Arch = Arch.current(),
) : ProfileCollector {
    override val source: ObservationSource = ObservationSource.EBPF
    private val observations = mutableListOf<ProfileObservation.Syscall>()
    private var droppedEvents: Int = 0
    private var drainComplete = true
    private val lock = Any()

    public fun onEvent(
        tgid: Int,
        tid: Int,
        syscallNr: Int,
        contextId: Long,
        ktimeNs: Long,
    ) {
        synchronized(lock) {
            val syscall = syscallFor(arch, syscallNr)
            observations += ProfileObservation.Syscall(
                correlation = ObservationCorrelation(tgid, Tid(tid), ktimeNs),
                source = ObservationSource.EBPF,
                name = when (syscall) {
                    is ObservedSyscall.Known -> syscall.syscall.name
                    is ObservedSyscall.Unknown -> "SYSCALL_$syscallNr"
                },
                contextId = contextId,
            )
        }
    }

    public fun onDrop() {
        synchronized(lock) {
            droppedEvents++
            drainComplete = false
        }
    }

    override fun start() = Unit

    override fun drain(): CollectorDrain =
        synchronized(lock) {
        CollectorDrain(java.util.List.copyOf(observations), droppedEvents, drainComplete)
    }

    override fun close() {
        synchronized(lock) {
            observations.clear()
            droppedEvents = 0
            drainComplete = true
        }
    }

    public companion object {
        public fun syscallFor(
            arch: Arch,
            nr: Int,
        ): ObservedSyscall =
            Syscall.entries
                .firstOrNull { syscall -> syscall.numberFor(arch).let { mapped -> mapped >= 0 && mapped == nr } }
                ?.let(ObservedSyscall::Known)
                ?: ObservedSyscall.Unknown("syscall:$nr")

        public fun syscallName(nr: Int): String =
            when (val syscall = syscallFor(Arch.current(), nr)) {
                is ObservedSyscall.Known -> syscall.syscall.name
                is ObservedSyscall.Unknown -> "SYSCALL_$nr"
            }
    }
}

/**
 * Compatibility name for the observation-producing Tier E adapter.
 * @deprecated Use [ProfileObservationEbpfCollector]; the raw ring collector is
 * [io.mazewall.profiler.tierE.collector.TierERingBufferCollector].
 */
@Deprecated("Use ProfileObservationEbpfCollector")
public class LiveEbpfCollector(
    arch: Arch = Arch.current(),
) : ProfileCollector {
    private val delegate = ProfileObservationEbpfCollector(arch)
    override val source: ObservationSource get() = delegate.source

    public fun onEvent(
        tgid: Int,
        tid: Int,
        syscallNr: Int,
        contextId: Long,
        ktimeNs: Long,
    ): Unit = delegate.onEvent(tgid, tid, syscallNr, contextId, ktimeNs)

    public fun onDrop(): Unit = delegate.onDrop()

    override fun start(): Unit = delegate.start()

    override fun drain(): CollectorDrain = delegate.drain()

    override fun close(): Unit = delegate.close()

    public companion object {
        public fun syscallName(nr: Int): String = ProfileObservationEbpfCollector.syscallName(nr)
    }
}
