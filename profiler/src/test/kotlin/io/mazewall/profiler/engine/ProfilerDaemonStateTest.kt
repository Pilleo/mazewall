package io.mazewall.profiler.engine

import io.mazewall.core.FileDescriptor
import io.mazewall.platform.daemon.UnixListenDaemonState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerDaemonStateTest {

    @Test
    fun `active listen state keeps socketPath`() {
        val serverFd = FileDescriptor.unixSocket(123)
        val listening = UnixListenDaemonState.Uninitialized.listening(serverFd, "/tmp/test.sock")
        val active = listening.active()
        assertEquals(serverFd, active.serverFd)
        assertEquals("/tmp/test.sock", active.socketPath)
    }

    @Test
    fun `profiler engine state is the shared delegate state`() {
        val engine = ProfilerDaemonEngine(socketPath = "/tmp/profiler-shared-state.sock")
        assertSame(engine.state, UnixListenDaemonState.Uninitialized)
        engine.triggerGlobalShutdown("unit")
        assertTrue(engine.state is UnixListenDaemonState.ShuttingDown)
    }
}
