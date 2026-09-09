package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeByte
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class SocketHelperProcessTest {
    @Test
    fun `every concurrent close waits until its child is reaped`() {
        repeat(8) {
            val before = ProcessHandle
                .current()
                .children()
                .map { it.pid() }
                .toList()
                .toSet()
            val helper = SocketHelperProcess.start()
            val ready = java.util.concurrent.CyclicBarrier(3)
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = (1..2).map {
                Thread.ofPlatform().start {
                    try {
                        ready.await()
                        helper.close()
                        assertTrue(ProcessHandle.current().children().noneMatch { it.pid() !in before && it.isAlive }, "close returned before child exit")
                    } catch (failure: Throwable) {
                        errors.add(failure)
                    }
                }
            }
            ready.await()
            threads.forEach {
                it.join(10000)
                assertFalse(it.isAlive)
            }
            assertTrue(errors.isEmpty(), errors.toString())
        }
    }

    @Test
    fun `helper connects original stream and datagram descriptions and remains reusable`() {
        SocketHelperProcess.start().use { helper ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5000
                socket(1).use { original ->
                    assertEquals(SocketHelperResult.Connected, helper.execute(SocketOperation.Connect(address(server.localPort)), original))
                    server.accept().use { peer ->
                        peer.soTimeout = 5000
                        write(original, 42)
                        assertEquals(42, peer.getInputStream().read())
                    }
                }
            }
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5000
                socket(2).use { original ->
                    assertEquals(SocketHelperResult.Connected, helper.execute(SocketOperation.Connect(address(server.localPort)), original))
                    write(original, 43)
                    val packet = DatagramPacket(ByteArray(1), 1)
                    server.receive(packet)
                    assertEquals(43, packet.data[0].toInt())
                }
            }
        }
    }

    @Test
    fun `nonblocking connect retains EINPROGRESS rather than creating a blocking stream`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            SocketHelperProcess.start().use { helper ->
                socket(1 or 2048).use { original ->
                    assertEquals(SocketHelperResult.Failed(115), helper.execute(SocketOperation.Connect(address(server.localPort)), original))
                    server.accept().close()
                }
            }
        }
    }

    @Test
    fun `protocol roundtrip is bounded and rejects invalid tags and lengths`() {
        val bytes = ByteArrayOutputStream()
        SocketHelperProtocol.writeOperation(DataOutputStream(bytes), SocketOperation.Connect(address(1234)))
        val decoded = SocketHelperProtocol.readOperation(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) as SocketOperation.Connect
        assertArrayEquals(address(1234), decoded.address)
        for (malformed in listOf(byteArrayOf(99), byteArrayOf(1, 0, 0, 0, -1), byteArrayOf(1, 0, 0, 1, 0))) {
            assertThrows(java.io.IOException::class.java) { SocketHelperProtocol.readOperation(DataInputStream(ByteArrayInputStream(malformed))) }
        }
    }

    private fun socket(type: Int): SupervisorOwnedFd =
        (SupervisorDescriptorResult.from(LinuxNative.networking.socket(2, type or 524288, 0), LinuxNative) as SupervisorDescriptorResult.Opened).descriptor

    private fun write(
        socket: SupervisorOwnedFd,
        byte: Int,
    ) {
        NativeArena.ofConfined().use { arena ->
            val data = arena.allocate(1)
            data.writeByte(0, byte.toByte())
            val result = LinuxNative.memory.write(socket.handle, data, 1)
            assertEquals(1L, (result as LinuxNative.SyscallResult.Success).value)
        }
    }

    private fun address(port: Int): ByteArray = byteArrayOf(2, 0, (port shr 8).toByte(), port.toByte(), 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
}
