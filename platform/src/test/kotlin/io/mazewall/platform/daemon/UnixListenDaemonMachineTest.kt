package io.mazewall.platform.daemon

import io.mazewall.core.FileDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import kotlin.reflect.KClass

internal class UnixListenDaemonMachineTest {

    companion object {
        private val server = FileDescriptor.unixSocket(20)
        private val listening = UnixListenDaemonState.Listening(server, "/tmp/mw.sock")
        private val active = UnixListenDaemonState.Active(server, "/tmp/mw.sock")

        data class TransitionTestCase(
            val name: String,
            val initialState: UnixListenDaemonState,
            val event: UnixListenDaemonEvent,
            val expectedState: UnixListenDaemonState,
            val expectedEffectTypes: List<KClass<out UnixListenDaemonEffect>> = emptyList(),
            val effectValidator: (List<UnixListenDaemonEffect>) -> Unit = {},
        ) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun daemonTransitions(): Stream<TransitionTestCase> = Stream.of(
            TransitionTestCase(
                name = "uninitialized binds to listening",
                initialState = UnixListenDaemonState.Uninitialized,
                event = UnixListenDaemonEvent.Bound(server, "/tmp/mw.sock"),
                expectedState = listening,
                expectedEffectTypes = listOf(UnixListenDaemonEffect.LogListening::class),
            ),
            TransitionTestCase(
                name = "listening announces ready to become active",
                initialState = listening,
                event = UnixListenDaemonEvent.ReadyAnnounced,
                expectedState = active,
                expectedEffectTypes = listOf(UnixListenDaemonEffect.PublishReady::class),
            ),
            TransitionTestCase(
                name = "active shutdown request becomes shutting down",
                initialState = active,
                event = UnixListenDaemonEvent.ShutdownRequested("test"),
                expectedState = UnixListenDaemonState.ShuttingDown,
                expectedEffectTypes = listOf(UnixListenDaemonEffect.LogShutdown::class),
                effectValidator = { effects ->
                    assertEquals("test", effects.filterIsInstance<UnixListenDaemonEffect.LogShutdown>().single().source)
                },
            ),
            TransitionTestCase(
                name = "second shutdown request is a no-op",
                initialState = UnixListenDaemonState.ShuttingDown,
                event = UnixListenDaemonEvent.ShutdownRequested("again"),
                expectedState = UnixListenDaemonState.ShuttingDown,
                expectedEffectTypes = emptyList(),
            ),
            TransitionTestCase(
                name = "accept loop finished from shutting down still closes the event server fd",
                initialState = UnixListenDaemonState.ShuttingDown,
                event = UnixListenDaemonEvent.AcceptLoopFinished(server),
                expectedState = UnixListenDaemonState.Terminated,
                expectedEffectTypes = listOf(UnixListenDaemonEffect.CloseServer::class),
                effectValidator = { effects ->
                    assertTrue(effects.any { it is UnixListenDaemonEffect.CloseServer && it.serverFd == server })
                },
            ),
            TransitionTestCase(
                name = "accept loop finished from active terminates and closes the server",
                initialState = active,
                event = UnixListenDaemonEvent.AcceptLoopFinished(server),
                expectedState = UnixListenDaemonState.Terminated,
                expectedEffectTypes = listOf(
                    UnixListenDaemonEffect.CloseServer::class,
                    UnixListenDaemonEffect.ClearConnectionTables::class,
                    UnixListenDaemonEffect.StopConnectionWorkers::class,
                ),
            ),
            TransitionTestCase(
                name = "terminated ignores bind",
                initialState = UnixListenDaemonState.Terminated,
                event = UnixListenDaemonEvent.Bound(server, "/tmp/mw.sock"),
                expectedState = UnixListenDaemonState.Terminated,
                expectedEffectTypes = emptyList(),
            ),
            TransitionTestCase(
                name = "terminated ignores ready",
                initialState = UnixListenDaemonState.Terminated,
                event = UnixListenDaemonEvent.ReadyAnnounced,
                expectedState = UnixListenDaemonState.Terminated,
                expectedEffectTypes = emptyList(),
            ),
            TransitionTestCase(
                name = "uninitialized cannot skip to active via ready",
                initialState = UnixListenDaemonState.Uninitialized,
                event = UnixListenDaemonEvent.ReadyAnnounced,
                expectedState = UnixListenDaemonState.Uninitialized,
                expectedEffectTypes = emptyList(),
            ),
            TransitionTestCase(
                name = "uninitialized shutdown still reaches shutting down",
                initialState = UnixListenDaemonState.Uninitialized,
                event = UnixListenDaemonEvent.ShutdownRequested("early"),
                expectedState = UnixListenDaemonState.ShuttingDown,
                expectedEffectTypes = listOf(UnixListenDaemonEffect.LogShutdown::class),
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("daemonTransitions")
    fun `verify daemon transition matrix`(testCase: TransitionTestCase) {
        val t = UnixListenDaemonMachine.evaluate(testCase.initialState, testCase.event)
        assertEquals(testCase.expectedState, t.state)
        for (expectedEffect in testCase.expectedEffectTypes) {
            assertTrue(
                t.effects.any { expectedEffect.isInstance(it) },
                "Expected effect of type ${expectedEffect.simpleName} but got ${t.effects}",
            )
        }
        if (testCase.expectedEffectTypes.isEmpty()) {
            assertTrue(t.effects.isEmpty(), "Expected no effects but got ${t.effects}")
        }
        testCase.effectValidator(t.effects)
    }

    @Test
    fun `compile-time exhaustive coverage of state and event variants`() {
        val states = listOf(
            UnixListenDaemonState.Uninitialized,
            listening,
            active,
            UnixListenDaemonState.ShuttingDown,
            UnixListenDaemonState.Terminated,
        )
        val events = listOf(
            UnixListenDaemonEvent.Bound(server, "/tmp/mw.sock"),
            UnixListenDaemonEvent.ReadyAnnounced,
            UnixListenDaemonEvent.ShutdownRequested("check"),
            UnixListenDaemonEvent.AcceptLoopFinished(server),
        )

        for (state in states) {
            // Compile-time exhaustive branch check
            when (state) {
                is UnixListenDaemonState.Uninitialized -> Unit
                is UnixListenDaemonState.Listening -> Unit
                is UnixListenDaemonState.Active -> Unit
                is UnixListenDaemonState.ShuttingDown -> Unit
                is UnixListenDaemonState.Terminated -> Unit
            }
            for (event in events) {
                // Compile-time exhaustive event check
                when (event) {
                    is UnixListenDaemonEvent.Bound -> Unit
                    is UnixListenDaemonEvent.ReadyAnnounced -> Unit
                    is UnixListenDaemonEvent.ShutdownRequested -> Unit
                    is UnixListenDaemonEvent.AcceptLoopFinished -> Unit
                }
                val transition = UnixListenDaemonMachine.evaluate(state, event)
                // Invariant: evaluate never returns null state
                assertEquals(false, transition.state == null)
            }
        }
    }

    @Test
    fun `apply cas-installs the next state then runs effects`() {
        val ref = AtomicReference<UnixListenDaemonState>(UnixListenDaemonState.Uninitialized)
        val seen = mutableListOf<UnixListenDaemonEffect>()
        UnixListenDaemonMachine.apply(ref, UnixListenDaemonEvent.Bound(server, "/tmp/mw.sock")) {
            seen.addAll(it)
        }
        assertEquals(listening, ref.get())
        assertTrue(seen.single() is UnixListenDaemonEffect.LogListening)
        UnixListenDaemonMachine.apply(ref, UnixListenDaemonEvent.ReadyAnnounced) { seen.addAll(it) }
        assertEquals(active, ref.get())
    }

    @Test
    fun `apply same-state skip does not replace the reference`() {
        val shutting = UnixListenDaemonState.ShuttingDown
        val ref = AtomicReference<UnixListenDaemonState>(shutting)
        UnixListenDaemonMachine.apply(ref, UnixListenDaemonEvent.ShutdownRequested("again")) { }
        assertSame(shutting, ref.get())
    }
}
