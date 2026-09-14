package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
