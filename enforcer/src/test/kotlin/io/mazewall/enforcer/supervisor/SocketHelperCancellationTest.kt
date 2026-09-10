package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SocketHelperCancellationTest {
    @Test
    fun `session cancellation kills a helper blocked in native accept and allows replacement`() {
        val childrenBefore = ProcessHandle
            .current()
            .children()
            .map { it.pid() }
            .toList()
            .toSet()
        val started = CountDownLatch(1)
        SocketHelperPool(1, 1) {
            SocketHelperProcess.start().also { started.countDown() }
        }.use { pool ->
            val session = pool.session()
            val listening = (SupervisorDescriptorResult.from(LinuxNative.networking.socket(2, 1 or 524288, 0), LinuxNative) as SupervisorDescriptorResult.Opened).descriptor
            NativeArena.ofConfined().use { arena ->
                val bytes = byteArrayOf(2, 0, 0, 0, 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
                val address = arena.allocate(16)
                ManagedSegment.copy(bytes, 0, address, 0, 16)
                assertTrue(LinuxNative.networking.bind(listening.handle, address, 16) is LinuxNative.SyscallResult.Success)
                assertTrue(LinuxNative.networking.listen(listening.handle, 1) is LinuxNative.SyscallResult.Success)
            }
            val result = session.submit(SocketOperation.Accept(0), listening)
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            session.close()
            assertTrue(result.isCompletedExceptionally)
            assertFalse(listening.handle.isLiveForIo())
            pool.session().use { replacement ->
                val udp = (SupervisorDescriptorResult.from(LinuxNative.networking.socket(2, 2 or 524288, 0), LinuxNative) as SupervisorDescriptorResult.Opened).descriptor
                val target = byteArrayOf(2, 0, 0, 9, 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
                assertEquals(SocketHelperResult.Connected, replacement.submit(SocketOperation.Connect(target), udp).get(10, TimeUnit.SECONDS))
            }
        }
        val remaining = ProcessHandle
            .current()
            .children()
            .filter { it.isAlive && it.pid() !in childrenBefore }
            .toList()
        assertTrue(remaining.isEmpty(), "cancelled helper must be reaped")
    }

    @Test
    fun `late accepted descriptor is disposed without publication`() {
        val fixture = SupervisorBoundaryFixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = SupervisorOwnedFd(
            TestFileDescriptors
            .replace<io.mazewall.core.FileDescriptorRole.Generic>(52200),
                fixture.engine.fileSystem,
        )
        val accepted = SupervisorOwnedFd(
            TestFileDescriptors
            .replace<io.mazewall.core.FileDescriptorRole.Generic>(52201),
                fixture.engine.fileSystem,
        )
        SocketHelperPool(1, 1) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return SocketHelperResult.Accepted(accepted, byteArrayOf(), 0)
                }

                override fun close() {
                    release.countDown()
                }
            }
        }.use { pool ->
            val session = pool.session()
            val result = session.submit(SocketOperation.Accept(0), source)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            session.close()
            assertTrue(result.isCompletedExceptionally)
        }
        assertEquals(listOf(52200, 52201), fixture.closed)
    }
}
