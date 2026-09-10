package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SupervisorInstallationLifetimeTest {
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
