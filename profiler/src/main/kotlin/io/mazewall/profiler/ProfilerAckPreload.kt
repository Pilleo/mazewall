package io.mazewall.profiler

import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.internal.ProfilerTraceListener
import io.mazewall.profiler.internal.TraceListenerState

/**
 * Initializes ACK/result types before USER_NOTIF is installed.
 * Dummy file I/O is not used: that does not load these classes and swallows failures.
 */
internal object ProfilerAckPreload {
    internal val requiredBinaryNames: List<String> =
        listOf(
            ProfilingResult::class.java.name,
            BillOfBehavior::class.java.name,
            BobCompiler::class.java.name,
            TraceEvent::class.java.name,
            ProfilerTraceListener::class.java.name,
            TraceListenerState::class.java.name,
            TraceListenerState.AwaitingEvent::class.java.name,
            TraceListenerState.ReadingHeader::class.java.name,
            TraceListenerState.ReadingSyscall::class.java.name,
            TraceListenerState.ReadingArguments::class.java.name,
            TraceListenerState.ProcessingEvent::class.java.name,
            TraceListenerState.Disconnected::class.java.name,
        )

    fun ensureLoaded() {
        val cl = ProfilerAckPreload::class.java.classLoader
        for (name in requiredBinaryNames) {
            Class.forName(name, true, cl)
        }
    }
}
