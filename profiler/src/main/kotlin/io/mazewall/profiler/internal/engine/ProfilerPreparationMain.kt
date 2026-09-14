package io.mazewall.profiler.internal.engine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionCause
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.combinePathResolution
import java.io.DataInputStream
import java.io.DataOutputStream

/** Independently terminable remote-memory reader. Stdout is exclusively the private binary pipe. */
internal object ProfilerPreparationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val input = DataInputStream(System.`in`)
        val output = DataOutputStream(System.out)
        while (true) {
            val request = ProfilerPreparationProtocol.readRequest(input)
            val event = NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val notification = request.notification
                    val tid = Tid(notification.pid)
                    val resolution = SyscallPathResolver(RealMemoryReader, SessionEventLedger()).resolveEvidence(tid, request.syscallName, notification.args)
                    SyscallEvent.fromResolution<SyscallEventState.Resolved>(tid, request.syscallName, notification.args.toList(), resolution)
                }
            }
            ProfilerPreparationProtocol.writeResponse(output, request, event)
        }
    }
}
