package io.mazewall.platform.seccomp.daemon

import io.mazewall.core.FileDescriptor
import io.mazewall.platform.daemon.UnixListenDaemonEvent
import io.mazewall.platform.daemon.UnixListenDaemonMachine
import io.mazewall.platform.daemon.UnixListenDaemonState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeccompDaemonMachineTest {

    @Test
    fun `seccomp machine is the shared listen-loop machine`() {
        val server = FileDescriptor.unixSocket(20)
        val event = UnixListenDaemonEvent.Bound(server, "/tmp/mw.sock")
        val viaAlias = SeccompDaemonMachine.evaluate(UnixListenDaemonState.Uninitialized, event)
        val viaShared = UnixListenDaemonMachine.evaluate(UnixListenDaemonState.Uninitialized, event)
        assertEquals(viaShared, viaAlias)
    }
}
