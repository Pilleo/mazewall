package io.mazewall.profiler.internal

import io.mazewall.profiler.engine.TraceEvent

/** Pure lifecycle reducer for trace-listener socket input. */
internal sealed interface TraceListenerEvent {
    data object HandshakeReceived : TraceListenerEvent

    data object AwaitEvent : TraceListenerEvent

    data class HeaderRead(
        val pid: Int,
    ) : TraceListenerEvent

    data class SyscallRead(
        val pid: Int,
        val nameLength: Int,
    ) : TraceListenerEvent

    data class ArgumentsRead(
        val pid: Int,
        val name: String,
        val count: Int,
    ) : TraceListenerEvent

    data class EventRead(
        val event: TraceEvent,
    ) : TraceListenerEvent

    data object SocketClosed : TraceListenerEvent
}

/** Keeps listener lifecycle assignment independent from socket reads and their failure paths. */
internal object TraceListenerMachine {
    fun evaluate(
        state: TraceListenerState,
        event: TraceListenerEvent,
    ): TraceListenerState =
        when (event) {
            TraceListenerEvent.HandshakeReceived,
            TraceListenerEvent.AwaitEvent,
            -> TraceListenerState.AwaitingEvent
            is TraceListenerEvent.HeaderRead ->
                if (state is TraceListenerState.AwaitingEvent) TraceListenerState.ReadingHeader(event.pid) else state
            is TraceListenerEvent.SyscallRead ->
                if (state is TraceListenerState.ReadingHeader && state.pid == event.pid) {
                    TraceListenerState.ReadingSyscall(event.pid, event.nameLength)
                } else {
                    state
                }
            is TraceListenerEvent.ArgumentsRead ->
                if (state is TraceListenerState.ReadingSyscall && state.pid == event.pid) {
                    TraceListenerState.ReadingArguments(event.pid, event.name, event.count)
                } else {
                    state
                }
            is TraceListenerEvent.EventRead ->
                if (state is TraceListenerState.ReadingArguments) {
                    TraceListenerState.ProcessingEvent(event.event)
                } else {
                    state
                }
            TraceListenerEvent.SocketClosed -> TraceListenerState.Disconnected
        }
}
