package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SupervisorRuntimeContextTest {
    @Test
    fun `registrations are isolated and session close removes only its own thread`() {
        val first = SupervisorRuntimeContext()
        val second = SupervisorRuntimeContext()
        val firstThread = Thread.currentThread()
        val secondThread = Thread("second-session")
        val tid = Tid(42)

        first.register(tid, firstThread)
        second.register(tid, secondThread)
        SupervisorSession(tid, first).close()

        assertNull(first.resolve(tid))
        assertSame(secondThread, second.resolve(tid))
    }
}
