package io.mazewall.profiler.internal.engine

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.seccomp.daemon.EventFdCompletionWakeup
import io.mazewall.profiler.ffi.RealProfilerTransport
import io.mazewall.profiler.ffi.TransportNativeEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class TransportNativeEngineEventFdTest {
    @Test
    fun `profiler transport bridge preserves eventfd signal and drain`() {
        val bridge = TransportNativeEngine(RealProfilerTransport, LinuxNative)
        EventFdCompletionWakeup.create(bridge).use { wakeup ->
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(wakeup.pollFd)
                pollFd.setEvents(NativeConstants.POLLIN)

                wakeup.signal()
                assertEquals(1L, bridge.raw.poll(pollFd.managed, 1L, 0).getOrThrow("poll signalled bridged eventfd"))
                wakeup.drain()
                pollFd.setRevents(0)
                assertEquals(0L, bridge.raw.poll(pollFd.managed, 1L, 0).getOrThrow("poll drained bridged eventfd"))
            }
        }
    }
}
