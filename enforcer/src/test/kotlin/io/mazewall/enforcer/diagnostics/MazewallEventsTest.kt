package io.mazewall.enforcer.diagnostics

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MazewallEventsTest {

    @AfterEach
    fun tearDown() {
        MazewallEvents.clear()
        MazewallEvents.failOnListenerError = false
    }

    @Test
    fun `listener receives emitted events`() {
        val received = CopyOnWriteArrayList<MazewallEvents.Event>()
        MazewallEvents.register { received += it }

        MazewallEvents.emit(MazewallEvents.FallbackEngaged("WARN_AND_BYPASS", "test"))
        MazewallEvents.emit(
            MazewallEvents.SelfVerificationResult(passed = true, detail = "ok"),
        )

        assertEquals(2, received.size)
        assertTrue(received[0] is MazewallEvents.FallbackEngaged)
        assertTrue(received[1] is MazewallEvents.SelfVerificationResult)
    }

    @Test
    fun `unregistered listener stops receiving`() {
        val received = CopyOnWriteArrayList<MazewallEvents.Event>()
        val listener = DiagnosticEventListener { received += it }
        MazewallEvents.register(listener)
        MazewallEvents.unregister(listener)

        MazewallEvents.emit(MazewallEvents.CetOutcome(armed = false, detail = "x"))
        assertEquals(0, received.size)
    }

    @Test
    fun `listener exceptions are swallowed by default and do not block other listeners`() {
        val received = CopyOnWriteArrayList<MazewallEvents.Event>()
        MazewallEvents.register { error("boom") }
        MazewallEvents.register { received += it }

        MazewallEvents.emit(MazewallEvents.LandlockApplied(processWide = true, abiVersion = 5))

        assertEquals(1, received.size)
    }

    @Test
    fun `failOnListenerError propagates for test seams`() {
        MazewallEvents.failOnListenerError = true
        MazewallEvents.register { error("boom") }

        assertFailsWith<IllegalStateException> {
            MazewallEvents.emit(MazewallEvents.LandlockApplied(processWide = false, abiVersion = 5))
        }
    }
}
