package io.mazewall.profiler.internal.engine
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfilerDaemonStateTest {
    @Test
    fun `active listen state keeps socketPath`() {
        val serverFd = TestFileDescriptors
            .replace<FileDescriptorRole.UnixSocket>(123)
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
