package io.mazewall.profiler.internal

import io.mazewall.profiler.engine.TraceEvent

/**
 * States representing the parser progress of a profiler trace listener reading from Unix socket.
 */
internal sealed interface TraceListenerState {
    /** The listener is active and waiting for a new event packet. */
    data object AwaitingEvent : TraceListenerState

    /** Reading the header metadata (PID) of the incoming trace event. */
    data class ReadingHeader(
        val pid: Int,
    ) : TraceListenerState

    /** Reading the system call name string from the input stream. */
    data class ReadingSyscall(
        val pid: Int,
        val nameLen: Int,
    ) : TraceListenerState

    /** Reading the system call argument values from the input stream. */
    data class ReadingArguments(
        val pid: Int,
        val name: String,
        val argsCount: Int,
    ) : TraceListenerState

    /** Processing the completely read trace event and generating stack trace context. */
    data class ProcessingEvent(
        val event: TraceEvent,
    ) : TraceListenerState

    /** The listener socket connection was terminated or closed. */
    data object Disconnected : TraceListenerState
}
