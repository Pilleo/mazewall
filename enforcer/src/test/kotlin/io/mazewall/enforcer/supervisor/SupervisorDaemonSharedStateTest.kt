package io.mazewall.enforcer.supervisor

import io.mazewall.platform.daemon.UnixListenDaemonState
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupervisorDaemonSharedStateTest {

    @Test
    fun `supervisor engine state is the shared delegate state`() {
        val engine = SupervisorDaemonEngine(socketPath = "/tmp/supervisor-shared-state.sock")
        assertSame(UnixListenDaemonState.Uninitialized, engine.state)
        engine.triggerGlobalShutdown("unit")
        assertTrue(engine.state is UnixListenDaemonState.ShuttingDown)
    }
}
