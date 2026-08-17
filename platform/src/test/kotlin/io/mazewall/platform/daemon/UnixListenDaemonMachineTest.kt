package io.mazewall.platform.daemon

import io.mazewall.core.FileDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class UnixListenDaemonMachineTest {

    private val server = FileDescriptor.unixSocket(20)
    private val listening = UnixListenDaemonState.Listening(server, "/tmp/mw.sock")
    private val active = UnixListenDaemonState.Active(server, "/tmp/mw.sock")

    @Test
    fun `uninitialized binds to listening`() {
        val t = UnixListenDaemonMachine.evaluate(
            UnixListenDaemonState.Uninitialized,
            UnixListenDaemonEvent.Bound(server, "/tmp/mw.sock"),
        )
        assertEquals(listening, t.state)
        assertTrue(t.effects.any { it is UnixListenDaemonEffect.LogListening })
    }

    @Test
    fun `listening announces ready to become active`() {
        val t = UnixListenDaemonMachine.evaluate(listening, UnixListenDaemonEvent.ReadyAnnounced)
        assertEquals(active, t.state)
        assertTrue(t.effects.any { it is UnixListenDaemonEffect.PublishReady })
    }

    @Test
    fun `active shutdown request becomes shutting down`() {
        val t = UnixListenDaemonMachine.evaluate(
            active,
            UnixListenDaemonEvent.ShutdownRequested("test"),
        )
        assertTrue(t.state is UnixListenDaemonState.ShuttingDown)
        assertEquals(
            "test",
            (t.effects.filterIsInstance<UnixListenDaemonEffect.LogShutdown>().single()).source,
        )
    }

    @Test
    fun `second shutdown request is a no-op`() {
        val shutting = UnixListenDaemonState.ShuttingDown
        val t = UnixListenDaemonMachine.evaluate(
            shutting,
            UnixListenDaemonEvent.ShutdownRequested("again"),
        )
        assertEquals(shutting, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `accept loop finished from shutting down still closes the event server fd`() {
        val t = UnixListenDaemonMachine.evaluate(
            UnixListenDaemonState.ShuttingDown,
            UnixListenDaemonEvent.AcceptLoopFinished(server),
        )
        assertEquals(UnixListenDaemonState.Terminated, t.state)
        assertTrue(t.effects.any { it is UnixListenDaemonEffect.CloseServer && it.serverFd == server })
    }

    @Test
    fun `accept loop finished from active terminates and closes the server`() {
        val t = UnixListenDaemonMachine.evaluate(active, UnixListenDaemonEvent.AcceptLoopFinished(server))
        assertEquals(UnixListenDaemonState.Terminated, t.state)
        assertTrue(t.effects.any { it is UnixListenDaemonEffect.CloseServer && it.serverFd == server })
        assertTrue(t.effects.any { it is UnixListenDaemonEffect.ClearConnectionTables })
        assertTrue(t.effects.any { it is UnixListenDaemonEffect.StopConnectionWorkers })
    }

    @Test
    fun `terminated ignores bind and ready`() {
        val bind = UnixListenDaemonMachine.evaluate(
            UnixListenDaemonState.Terminated,
            UnixListenDaemonEvent.Bound(server, "/tmp/mw.sock"),
        )
        val ready = UnixListenDaemonMachine.evaluate(
            UnixListenDaemonState.Terminated,
            UnixListenDaemonEvent.ReadyAnnounced,
        )
        assertEquals(UnixListenDaemonState.Terminated, bind.state)
        assertEquals(UnixListenDaemonState.Terminated, ready.state)
        assertTrue(bind.effects.isEmpty())
        assertTrue(ready.effects.isEmpty())
    }

    @Test
    fun `uninitialized cannot skip to active via ready`() {
        val t = UnixListenDaemonMachine.evaluate(
            UnixListenDaemonState.Uninitialized,
            UnixListenDaemonEvent.ReadyAnnounced,
        )
        assertEquals(UnixListenDaemonState.Uninitialized, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `uninitialized shutdown still reaches shutting down`() {
        val t = UnixListenDaemonMachine.evaluate(
            UnixListenDaemonState.Uninitialized,
            UnixListenDaemonEvent.ShutdownRequested("early"),
        )
        assertTrue(t.state is UnixListenDaemonState.ShuttingDown)
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
