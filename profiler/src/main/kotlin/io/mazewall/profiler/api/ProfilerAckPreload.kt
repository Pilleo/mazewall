package io.mazewall.profiler.api

import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.seccomp.daemon.SeccompStreamEnd
import io.mazewall.profiler.api.TraceEvent
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.internal.ConnectResolutionWire
import io.mazewall.profiler.internal.PathResolutionWire
import io.mazewall.profiler.internal.ProfilerTraceListener
import io.mazewall.profiler.internal.TraceEventDecodeResult
import io.mazewall.profiler.internal.TraceEventDecoder
import io.mazewall.profiler.internal.TraceEventQueue
import io.mazewall.profiler.internal.TraceListenerEvent
import io.mazewall.profiler.internal.TraceListenerState
import io.mazewall.profiler.internal.TraceStreamEndWire
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
            TraceEvent.Generic::class.java.name,
            TraceEvent.Open::class.java.name,
            TraceEvent.Exec::class.java.name,
            TraceEvent.Mmap::class.java.name,
            TraceEvent.Socket::class.java.name,
            TraceEvent.FsMutation::class.java.name,
            ProfilerTraceListener::class.java.name,
            TraceListenerState::class.java.name,
            TraceListenerState.AwaitingEvent::class.java.name,
            TraceListenerState.ReadingHeader::class.java.name,
            TraceListenerState.ReadingSyscall::class.java.name,
            TraceListenerState.ReadingArguments::class.java.name,
            TraceListenerState.ProcessingEvent::class.java.name,
            TraceListenerState.Disconnected::class.java.name,
            TraceListenerState.HandshakeRejected::class.java.name,
            TraceListenerState.Drained::class.java.name,
            TraceListenerState.ProtocolViolation::class.java.name,
            TraceListenerEvent::class.java.name,
            TraceListenerEvent.HandshakeReceived::class.java.name,
            TraceListenerEvent.HandshakeRejected::class.java.name,
            TraceListenerEvent.AwaitEvent::class.java.name,
            TraceListenerEvent.HeaderRead::class.java.name,
            TraceListenerEvent.SyscallRead::class.java.name,
            TraceListenerEvent.ArgumentsRead::class.java.name,
            TraceListenerEvent.EventRead::class.java.name,
            TraceListenerEvent.SocketClosed::class.java.name,
            TraceListenerEvent.DrainConfirmed::class.java.name,
            TraceListenerEvent.ProtocolRejected::class.java.name,
            TraceEventDecoder::class.java.name,
            TraceEventDecodeResult::class.java.name,
            TraceEventDecodeResult.Event::class.java.name,
            TraceEventDecodeResult.ProtocolViolation::class.java.name,
            PathResolutionWire::class.java.name,
            ConnectResolutionWire::class.java.name,
            TraceStreamEndWire::class.java.name,
            SeccompStreamEnd::class.java.name,
            SyscallEvent::class.java.name,
            PathResolution::class.java.name,
            PathResolution.NotApplicable::class.java.name,
            PathResolution.Resolved::class.java.name,
            PathResolution.Partial::class.java.name,
            PathResolution.Failed::class.java.name,
            PathResolutionFailure::class.java.name,
            PathResolutionCause::class.java.name,
            PathResolutionFailure.Reason::class.java.name,
            PathResolutionFailure.Reason.VmReadDenied::class.java.name,
            PathResolutionFailure.Reason.Missing::class.java.name,
            PathResolutionFailure.Reason.DirFdUnresolved::class.java.name,
            PathResolutionFailure.Reason.Truncated::class.java.name,
            ConnectResolution::class.java.name,
            ConnectResolution.NotApplicable::class.java.name,
            ConnectResolution.Resolved::class.java.name,
            ConnectResolution.Failed::class.java.name,
            ConnectResolution.Reason::class.java.name,
            NetworkEndpoint::class.java.name,
            OpenFlagsEvidence::class.java.name,
            OpenFlagsEvidence.Complete::class.java.name,
            OpenFlagsEvidence.Incomplete::class.java.name,
            AddressFamily::class.java.name,
            AddressFamily.Known::class.java.name,
            AddressFamily.Unknown::class.java.name,
            SocketType::class.java.name,
            IpProtocol::class.java.name,
            MmapProtection::class.java.name,
            MmapFlags::class.java.name,
            MmapFileDescriptor::class.java.name,
            "io.mazewall.profiler.api.SyscallPaths",
            "io.mazewall.profiler.api.SyscallEventKt",
            "io.mazewall.profiler.api.PathResolutionKt",
            "io.mazewall.profiler.api.ConnectResolutionKt",
            "io.mazewall.profiler.api.ProfilerSessionKt",
            "io.mazewall.profiler.api.TraceEventKt",
            "io.mazewall.profiler.internal.TraceEventDecoderKt",
            "io.mazewall.profiler.internal.DecodedTraceEvent",
            "io.mazewall.profiler.internal.ProfilerTraceListener\$CollectionOutcome",
            io.mazewall.profiler.internal.BenchmarkRawSyscallDiagnostics::class.java.name,
            io.mazewall.profiler.internal.BenchmarkRawSyscallDiagnostics.Capture::class.java.name,
            io.mazewall.profiler.internal.BenchmarkRawSyscallDiagnostics.Record::class.java.name,
            io.mazewall.profiler.internal.TraceListenerProtocolException::class.java.name,
            io.mazewall.profiler.internal.TraceCommandWriteResult.Written::class.java.name,
            io.mazewall.profiler.internal.TraceCommandWriteResult::class.java.name,
            io.mazewall.profiler.internal.TraceCommandWriteResult.Companion::class.java.name,
            io.mazewall.profiler.internal.TraceCommandWriteResult.NativeFailure::class.java.name,
            io.mazewall.profiler.internal.TraceCommandWriteResult.ShortWrite::class.java.name,
            io.mazewall.profiler.internal.TraceCommandWriteResult.Threw::class.java.name,
        )

    fun ensureLoaded() {
        val cl = ProfilerAckPreload::class.java.classLoader
        for (name in requiredBinaryNames) {
            Class.forName(name, true, cl)
        }
        // Initialize send, suspended receive and retirement before TSYNC can filter their class I/O.
        val queue = TraceEventQueue(1)
        val warmup = TraceEvent.Generic(Tid(1), "GETPID", emptyList())
        runBlocking {
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
                for (event in queue.channel) check(event === warmup)
            }
            check(queue.offer(warmup))
            queue.close()
            consumer.join()
        }
    }
}
