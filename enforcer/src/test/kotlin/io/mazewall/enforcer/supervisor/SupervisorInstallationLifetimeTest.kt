package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SupervisorInstallationLifetimeTest {
    @Test fun `failure closes a listener once even when owner later retires`() {
        val closes = AtomicInteger()
        val lifetime = SupervisorInstallationLifetime(Thread.currentThread(), SupervisorRuntimeContext())
        lifetime.attach(AutoCloseable { closes.incrementAndGet() })
        lifetime.fail(IllegalStateException("listener failed"))
        lifetime.fail(IllegalStateException("second failure"))
        lifetime.close()
        assertEquals(1, closes.get())
    }

    @Test fun `listener attached after installation failure is immediately stopped`() {
        val closes = AtomicInteger()
        val lifetime = SupervisorInstallationLifetime(Thread.currentThread(), SupervisorRuntimeContext())
        lifetime.fail(IllegalStateException("startup expired"))
        try {
            lifetime.attach(AutoCloseable { closes.incrementAndGet() })
            assertEquals(1, closes.get())
            assertFailsWith<IllegalStateException> { lifetime.requireReusable() }
        } finally {
            lifetime.close()
        }
        assertEquals(1, closes.get())
    }

    @Test fun `reactor failure retires runtime and rejects another task lease`() {
        val release = CountDownLatch(1)
        val owner = Thread { release.await() }.apply { start() }
        val runtime = SupervisorRuntimeContext()
        val lifetime = SupervisorInstallationLifetime(owner, runtime)
        try {
            lifetime.fail(IllegalStateException("validation reactor exited"))

            assertFailsWith<IllegalStateException> { lifetime.requireReusable() }
            assertFailsWith<IllegalStateException> { runtime.register(Tid(51), owner) }
        } finally {
            release.countDown()
            lifetime.close()
            owner.join(5_000)
        }
    }

    @Test fun `owner termination retires runtime and listener exactly once`() {
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val closes = AtomicInteger()
        val owner = Thread { release.await() }.apply { start() }
        val runtime = SupervisorRuntimeContext()
        val lifetime = SupervisorInstallationLifetime(owner, runtime)
        lifetime.attach(
            AutoCloseable {
            closes.incrementAndGet()
            stopped.countDown()
        },
        )
        lifetime.start()
        try {
            runtime.register(Tid(50), owner)
            SupervisorSession(Tid(50), runtime).close()
            assertEquals(0, closes.get())
            runtime.register(Tid(50), owner)
            release.countDown()
            owner.join(5000)
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { runtime.register(Tid(50), owner) }
            lifetime.close()
            assertEquals(1, closes.get())
        } finally {
            release.countDown()
            lifetime.close()
            owner.join(5000)
        }
    }
}
