package io.mazewall.profiler.collector

import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
import io.mazewall.profiler.api.ObservationCorrelation
import io.mazewall.profiler.api.ObservationSource
import io.mazewall.profiler.api.ObservedSyscall
import io.mazewall.profiler.api.ProfileObservation

/** Converts live Tier E records into strategy-neutral profile observations. */
internal class ProfileObservationEbpfCollector(
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
        private const val MAX_SYSCALL_NR = 512
        private val amd64SyscallTable = arrayOfNulls<Syscall>(MAX_SYSCALL_NR + 1).also { table ->
            for (syscall in Syscall.entries) {
                val nr = syscall.numberFor(Arch.AMD64)
                if (nr in 0..MAX_SYSCALL_NR) {
                    table[nr] = syscall
                }
            }
        }
        private val aarch64SyscallTable = arrayOfNulls<Syscall>(MAX_SYSCALL_NR + 1).also { table ->
            for (syscall in Syscall.entries) {
                val nr = syscall.numberFor(Arch.AARCH64)
                if (nr in 0..MAX_SYSCALL_NR) {
                    table[nr] = syscall
                }
            }
        }

        public fun syscallFor(
            arch: Arch,
            nr: Int,
        ): ObservedSyscall {
            val table = when (arch) {
                Arch.AMD64 -> amd64SyscallTable
                Arch.AARCH64 -> aarch64SyscallTable
                else -> null
            }
            val syscall = if (table != null && nr in 0..MAX_SYSCALL_NR) table[nr] else null
            return syscall?.let(ObservedSyscall::Known) ?: ObservedSyscall.Unknown("syscall:$nr")
        }

        public fun syscallName(nr: Int): String =
            when (val syscall = syscallFor(Arch.current(), nr)) {
                is ObservedSyscall.Known -> syscall.syscall.name
                is ObservedSyscall.Unknown -> "SYSCALL_$nr"
            }
    }
}
