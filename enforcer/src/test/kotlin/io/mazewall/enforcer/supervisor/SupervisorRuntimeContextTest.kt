package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SupervisorRuntimeContextTest {
    @Test
    fun `closing task session permits sequential registration on permanent runtime`() {
        val runtime = SupervisorRuntimeContext()
        val tid = Tid(44)
        runtime.register(tid)
        SupervisorSession(tid, runtime).close()
        runtime.register(tid)
        assertSame(Thread.currentThread(), runtime.resolve(tid))
        runtime.close()
    }

    @Test
    fun `closing nested task lease preserves outer registration`() {
        val runtime = SupervisorRuntimeContext()
        val tid = Tid(45)
        runtime.register(tid)
        val outer = SupervisorSession(tid, runtime)
        runtime.register(tid)
        val inner = SupervisorSession(tid, runtime)
        inner.close()
        inner.close()
        assertSame(Thread.currentThread(), runtime.resolve(tid))
        outer.close()
        assertNull(runtime.resolve(tid))
        runtime.close()
    }

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
