package io.mazewall.profiler

import io.mazewall.core.Tid
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ProfilerSessionStateTest {
    @Test
    fun `session thread registrations are isolated`() {
        val first = ProfilerSessionState()
        val second = ProfilerSessionState()
        val thread = Thread.currentThread()

        first.register(Tid(101), thread)

        assertSame(thread, first.threadFor(Tid(101)))
        assertNull(second.threadFor(Tid(101)))
    }
}
