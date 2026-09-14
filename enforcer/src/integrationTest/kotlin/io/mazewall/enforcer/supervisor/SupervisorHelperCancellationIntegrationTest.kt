package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@NeedsFreshJvm
class SupervisorHelperCancellationIntegrationTest {
    @Test
    fun `saturated native accept pool cancels queued and active work and reaps before replacement`() {
        val started = CountDownLatch(1)
        val children = ProcessHandle
            .current()
            .children()
            .map { it.pid() }
            .toList()
            .toSet()
        SocketHelperPool(1, 1) {
            SocketHelperProcess.start().also { started.countDown() }
        }.use { pool ->
            val active = pool.session()
            val listening = listeningSocket()
            val pending = active.submit(SocketOperation.Accept(0), listening)
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertFalse(pending.isDone)
            val queued = pool.session()
            val queuedSocket = socket(2)
            val queuedResult = queued.submit(SocketOperation.Connect(addressBytes(9)), queuedSocket)
            pool.session().use { overload ->
                val rejected = socket(2)
                assertEquals(SocketHelperResult.Failed(11), overload.submit(SocketOperation.Connect(addressBytes(9)), rejected).get(5, TimeUnit.SECONDS))
                assertFalse(rejected.handle.isLiveForIo())
            }
            queued.cancel()
            assertTrue(queuedResult.isCancelled)
            assertFalse(queuedSocket.handle.isLiveForIo())
            active.cancel()
            active.close()
            assertTrue(pending.isCancelled)
            assertFalse(listening.handle.isLiveForIo())
            pool.session().use { replacement ->
                assertEquals(SocketHelperResult.Connected, replacement.submit(SocketOperation.Connect(addressBytes(9)), socket(2)).get(10, TimeUnit.SECONDS))
            }
            // Pool shutdown also cancels and reaps native work still pending at teardown.
            val last = pool.session()
            val lastSocket = listeningSocket()
            val blockedAccept = last.submit(SocketOperation.Accept(0), lastSocket)
            assertFalse(blockedAccept.isDone)
            pool.close()
            assertTrue(blockedAccept.isCancelled)
            assertFalse(lastSocket.handle.isLiveForIo())
        }
        assertTrue(ProcessHandle.current().children().noneMatch { it.isAlive && it.pid() !in children })
    }

    private fun socket(type: Int): SupervisorOwnedFd =
        (SupervisorDescriptorResult.from(LinuxNative.networking.socket(2, type or 524288, 0), LinuxNative) as SupervisorDescriptorResult.Opened).descriptor

    private fun listeningSocket(): SupervisorOwnedFd {
        val listening = socket(1)
        NativeArena.ofConfined().use { arena ->
            val address = arena.allocate(16)
            ManagedSegment.copy(addressBytes(0), 0, address, 0, 16)
            assertTrue(LinuxNative.networking.bind(listening.handle, address, 16) is LinuxNative.SyscallResult.Success)
            assertTrue(LinuxNative.networking.listen(listening.handle, 1) is LinuxNative.SyscallResult.Success)
        }
        return listening
    }

    private fun addressBytes(port: Int) = byteArrayOf(2, 0, 0, port.toByte(), 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
}
