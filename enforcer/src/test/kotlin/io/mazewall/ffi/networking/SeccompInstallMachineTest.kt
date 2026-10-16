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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SeccompInstallMachineTest {
    @Test fun `late kernel completion preserves failure and records irreversible application`() {
        val cause = IllegalStateException("deadline")
        val failed = SeccompInstallState.Failed(SeccompInstallStage.Prepared, cause)
        val late = SeccompInstallMachine.evaluate(failed, SeccompInstallEvent.Advanced(SeccompInstallStage.Applied))
        val next = assertIs<SeccompInstallState.Failed>(late.state)
        assertSame(cause, next.cause)
        assertEquals(SeccompInstallStage.Applied, next.stage)
        assertEquals(SeccompInstallEffect.Release, late.effect)
        val ready = SeccompInstallMachine.evaluate(next, SeccompInstallEvent.Advanced(SeccompInstallStage.Ready))
        assertIs<SeccompInstallState.Failed>(ready.state)
    }

    @Test fun `failed handshakes retain the first failure and completed stage`() {
        val cause = IllegalStateException("send failed")
        val first = SeccompInstallMachine.evaluate(
            SeccompInstallState.Pending(SeccompInstallStage.Applied),
            SeccompInstallEvent.Failed(cause),
        )
        val repeated = SeccompInstallMachine.evaluate(first.state, SeccompInstallEvent.Failed(Error("cleanup failed")))
        assertSame(first.state, repeated.state)
        assertSame(cause, assertIs<SeccompInstallState.Failed>(repeated.state).cause)
    }

    @Test fun `readiness cannot renew the deadline used for installation`() {
        var now = 0L
        val published = AtomicInteger()
        val handshake = SeccompInstallHandshake({ published.incrementAndGet() }, Deadline(10), { now })
        handshake.advance(SeccompInstallStage.Applied)
        handshake.publishApplied()
        handshake.publishApplied()
        handshake.await(CountDownLatch(0))
        now = 10
        assertFailsWith<IllegalStateException> { handshake.await(CountDownLatch(0)) }
        assertEquals(1, published.get())
    }

    @Test fun `interrupted installation wait restores interruption after readiness`() {
        val handshake = SeccompInstallHandshake({})
        Thread.currentThread().interrupt()
        try {
            handshake.await(CountDownLatch(0))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
