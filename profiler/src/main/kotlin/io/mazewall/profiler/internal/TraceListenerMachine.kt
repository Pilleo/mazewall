package io.mazewall.profiler.internal

import io.mazewall.profiler.api.TraceEvent

/** Pure lifecycle reducer for trace-listener socket input. */
internal sealed interface TraceListenerEvent {
    data object HandshakeReceived : TraceListenerEvent

    data object HandshakeRejected : TraceListenerEvent

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
    /** Preloads every sealed event implementation used after USER_NOTIF installation. */
    fun preload() {
        listOf(
            TraceListenerEvent.HandshakeReceived,
            TraceListenerEvent.HandshakeRejected,
            TraceListenerEvent.AwaitEvent,
            TraceListenerEvent.SocketClosed,
            TraceListenerState.AwaitingEvent,
            TraceListenerState.Disconnected,
            TraceListenerState.HandshakeRejected,
        )
    }

    fun evaluate(
        state: TraceListenerState,
        event: TraceListenerEvent,
    ): TraceListenerState {
        if (state is TraceListenerState.ProtocolViolation || state is TraceListenerState.HandshakeRejected) return state
        return when (event) {
            TraceListenerEvent.HandshakeReceived ->
                if (state is TraceListenerState.Disconnected) TraceListenerState.AwaitingEvent else violation(state, event)
            TraceListenerEvent.HandshakeRejected ->
                if (state is TraceListenerState.Disconnected) TraceListenerState.HandshakeRejected else violation(state, event)
            TraceListenerEvent.AwaitEvent ->
                if (state is TraceListenerState.AwaitingEvent || state is TraceListenerState.ProcessingEvent) TraceListenerState.AwaitingEvent else violation(state, event)
            is TraceListenerEvent.HeaderRead -> readHeader(state, event)
            is TraceListenerEvent.SyscallRead -> readSyscall(state, event)
            is TraceListenerEvent.ArgumentsRead -> readArguments(state, event)
            is TraceListenerEvent.EventRead -> readEvent(state, event)
            TraceListenerEvent.SocketClosed ->
                TraceListenerState.Disconnected
        }
    }

    private fun readHeader(
        state: TraceListenerState,
        event: TraceListenerEvent.HeaderRead,
    ): TraceListenerState = if (state is TraceListenerState.AwaitingEvent && event.pid > 0) TraceListenerState.ReadingHeader(event.pid) else violation(state, event)

    private fun readSyscall(
        state: TraceListenerState,
        event: TraceListenerEvent.SyscallRead,
    ): TraceListenerState = if (state is TraceListenerState.ReadingHeader && state.pid == event.pid) TraceListenerState.ReadingSyscall(event.pid, event.nameLength) else violation(state, event)

    private fun readArguments(
        state: TraceListenerState,
        event: TraceListenerEvent.ArgumentsRead,
    ): TraceListenerState =
        if (state is TraceListenerState.ReadingSyscall && state.pid == event.pid) TraceListenerState.ReadingArguments(event.pid, event.name, event.count) else violation(state, event)

    private fun readEvent(
        state: TraceListenerState,
        event: TraceListenerEvent.EventRead,
    ): TraceListenerState = if (state is TraceListenerState.ReadingArguments && state.pid == event.event.tid.value) TraceListenerState.ProcessingEvent(event.event) else violation(state, event)

    private fun violation(
        state: TraceListenerState,
        event: TraceListenerEvent,
    ): TraceListenerState.ProtocolViolation = TraceListenerState.ProtocolViolation("Invalid listener transition: ${state.javaClass.simpleName} / ${event.javaClass.simpleName}")
}

/** Peer/parser protocol failure, distinct from native transport failure or a programmer check. */
internal class TraceListenerProtocolException(
    reason: String,
) : java.io.IOException(reason)
