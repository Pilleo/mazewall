package io.mazewall.enforcer.supervisor

import io.mazewall.core.FileDescriptor
import io.mazewall.platform.daemon.UnixListenDaemonEffect
import io.mazewall.platform.daemon.UnixListenDaemonEvent
import io.mazewall.platform.daemon.UnixListenDaemonMachine
import io.mazewall.platform.daemon.UnixListenDaemonState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupervisorDaemonSharedStateTest {

    @Test
    fun `supervisor uses the shared listen-loop machine`() {
        val server = FileDescriptor.unixSocket(21)
        val listening = UnixListenDaemonState.Listening(server, "/tmp/sup.sock")
        val ready = UnixListenDaemonMachine.evaluate(listening, UnixListenDaemonEvent.ReadyAnnounced)
        val active = ready.state as UnixListenDaemonState.Active
        assertEquals("/tmp/sup.sock", active.socketPath)
        assertTrue(ready.effects.single() is UnixListenDaemonEffect.PublishReady)

        val shutdown = UnixListenDaemonMachine.evaluate(
            active,
            UnixListenDaemonEvent.ShutdownRequested("supervisor-unit"),
        )
        assertTrue(shutdown.state is UnixListenDaemonState.ShuttingDown)
    }

    @Test
    fun `supervisor engine state is the shared delegate state`() {
        val engine = SupervisorDaemonEngine(socketPath = "/tmp/supervisor-shared-state.sock")
        assertSame(UnixListenDaemonState.Uninitialized, engine.state)
        engine.triggerGlobalShutdown("unit")
        assertTrue(engine.state is UnixListenDaemonState.ShuttingDown)
    }
}
