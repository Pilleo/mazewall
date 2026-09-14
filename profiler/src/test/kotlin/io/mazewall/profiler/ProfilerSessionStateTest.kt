package io.mazewall.profiler.api
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
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
