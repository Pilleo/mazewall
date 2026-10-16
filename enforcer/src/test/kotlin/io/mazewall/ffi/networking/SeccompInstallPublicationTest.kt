package io.mazewall.ffi.networking

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class SeccompInstallPublicationTest {
    @Test fun `timeout cleanup cannot finish before an active applied callback publishes`() {
        publicationRace(callbackFailure = null)
    }

    @Test fun `timeout cleanup observes an active applied callback failure without invoking it twice`() {
        publicationRace(callbackFailure = IllegalStateException("publication failed"))
    }

    private fun publicationRace(callbackFailure: Throwable?) {
        val now = AtomicLong()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        val applied = AtomicInteger()
        val invocations = AtomicInteger()
        val coordinatorFailure = AtomicReference<Throwable?>()
        val cleanupFailure = AtomicReference<Throwable?>()
        val observedApplied = AtomicInteger(-1)
        val handshake = SeccompInstallHandshake(
            {
                invocations.incrementAndGet()
                entered.countDown()
                release.await()
                applied.incrementAndGet()
                if (callbackFailure != null) throw callbackFailure
            },
            Deadline(10),
            now::get,
        )
        handshake.advance(SeccompInstallStage.Applied)
        val coordinator = Thread {
            try {
                handshake.publishApplied()
            } catch (failure: Throwable) {
                coordinatorFailure.set(failure)
            }
        }
        val cleanup = Thread {
            cleanupStarted.countDown()
            try {
                handshake.publishApplied()
            } catch (failure: Throwable) {
                cleanupFailure.set(failure)
            } finally {
                observedApplied.set(applied.get())
                cleanupDone.countDown()
            }
        }
        try {
            coordinator.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            now.set(10)
            assertFailsWith<IllegalStateException> { handshake.await(handshake.proceed) }
            cleanup.start()
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
            assertFalse(cleanupDone.await(200, TimeUnit.MILLISECONDS), "claiming publication must not let timeout cleanup skip its unfinished callback")
            release.countDown()
            assertTrue(cleanupDone.await(5, TimeUnit.SECONDS))
            coordinator.join(5000)
            assertEquals(1, invocations.get())
            assertEquals(1, observedApplied.get(), "irreversible state must be visible before timeout cleanup returns")
            assertSame(callbackFailure, coordinatorFailure.get())
            assertSame(callbackFailure, cleanupFailure.get())
        } finally {
            release.countDown()
            coordinator.join(5000)
            if (cleanup.state != Thread.State.NEW) cleanup.join(5000)
        }
    }
}
