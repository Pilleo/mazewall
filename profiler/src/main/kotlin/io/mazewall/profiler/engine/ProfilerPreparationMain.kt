package io.mazewall.profiler.engine

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
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
