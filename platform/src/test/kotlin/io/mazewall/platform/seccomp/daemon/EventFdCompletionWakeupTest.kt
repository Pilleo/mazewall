package io.mazewall.platform.seccomp.daemon

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.native.LinuxNative
import kotlin.test.Test
import kotlin.test.assertEquals

class EventFdCompletionWakeupTest {
    @Test
    fun `signal makes the owned eventfd readable until the reactor drains it`() {
        EventFdCompletionWakeup.create(LinuxNative).use { wakeup ->
            NativeArena.ofConfined().use { arena ->
                val pollFd = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                pollFd.setFd(wakeup.pollFd)
                pollFd.setEvents(NativeConstants.POLLIN)

                wakeup.signal()
                assertEquals(1L, LinuxNative.raw.poll(pollFd.managed, 1L, 0).getOrThrow("poll signalled eventfd"))
                assertEquals(NativeConstants.POLLIN, pollFd.getRevents())

                wakeup.drain()
                pollFd.setRevents(0)
                assertEquals(0L, LinuxNative.raw.poll(pollFd.managed, 1L, 0).getOrThrow("poll drained eventfd"))
            }
        }
    }
}
