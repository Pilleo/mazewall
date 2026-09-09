package io.mazewall.portal.worker

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.NativeFileSystem
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.OpenFlags
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalIncompleteFrameException
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPayload
import io.mazewall.portal.PortalReadTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

class PortalWorkerDriverTest {
    @Test
    fun `capability and transport cleanup failures are suppressed on reflected fatal error`(
        @TempDir directory: Path,
    ) {
        Files.writeString(directory.resolve("grant"), "x")
        val capability = openCapability(directory.resolve("grant"))
        val capabilityFd = capability.borrow { it.value }
        val fatal = AssertionError("fatal guest")
        val capabilityCleanup = AssertionError("fatal capability cleanup")
        val transportCleanup = IOException("transport cleanup")
        val runtime = LinuxNative.snapshot()
        LinuxNative.setEngine(object : NativeEngine by runtime {
            override val fileSystem = object : NativeFileSystem by runtime.fileSystem {
                override fun close(
                    fd: io.mazewall.core.FileDescriptor<*, io.mazewall.core.FdState.Open, io.mazewall.core.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd.value == capabilityFd) throw capabilityCleanup
                    return runtime.fileSystem.close(fd)
                }
            }
        })
        val transport = FakeTransport(
            listOf(PortalWorkerReceive.Frame(request(), listOf(capability))),
            closeFailure = transportCleanup,
        )
        val driver = PortalWorkerDriver(transport) { _, _, _ -> throw InvocationTargetException(fatal) }
        try {
            assertSame(fatal, assertThrows(AssertionError::class.java) { driver.run() })
            assertEquals(0, transport.sendAttempts)
            assertEquals(listOf(capabilityCleanup, transportCleanup), fatal.suppressed.toList())
            assertEquals(PortalWorkerState.Stopped, driver.state)
        } finally {
            LinuxNative.resetToDefault()
            capability.close()
        }
    }

    @Test
    fun `reflective fatal error preserves identity sends nothing and retires transport`() {
        val fatal = AssertionError("fatal guest failure")
        val closeFailure = IOException("transport close failed")
        val transport = FakeTransport(listOf(PortalWorkerReceive.Frame(request())), closeFailure = closeFailure)
        val driver = PortalWorkerDriver(transport) { _, _, _ ->
            throw InvocationTargetException(fatal)
        }

        assertSame(fatal, assertThrows(AssertionError::class.java) { driver.run() })
        assertEquals(0, transport.sendAttempts)
        assertEquals(PortalWorkerState.Stopped, driver.state)
        assertTrue(transport.closed)
        assertTrue(fatal.suppressed.contains(closeFailure), "terminal cleanup failure must not replace fatal identity")
    }

    @Test
    fun `failed response write is terminal and never appends an error frame`() {
        val transport = FakeTransport(
            listOf(PortalWorkerReceive.Frame(request())),
            sendFailure = IOException("partial write"),
        )
        val driver = PortalWorkerDriver(transport) { _, _, _ -> byteArrayOf(1) }

        assertThrows(IOException::class.java) { driver.run() }

        assertEquals(1, transport.sendAttempts)
        assertEquals(PortalWorkerState.Stopped, driver.state)
    }

    @Test
    fun `idle timeout continues but partial frame timeout terminates`() {
        val transport = FakeTransport(
            listOf(PortalWorkerReceive.Idle, PortalWorkerReceive.Failure(PortalIncompleteFrameException())),
        )
        val driver = PortalWorkerDriver(transport) { _, _, _ -> ByteArray(0) }

        assertThrows(PortalIncompleteFrameException::class.java) { driver.run() }

        assertEquals(PortalWorkerState.Stopped, driver.state)
        assertEquals(2, transport.receiveAttempts)
    }

    @Test
    fun `reflective guest failure is unwrapped at the single dispatch boundary`() {
        val transport = FakeTransport(listOf(PortalWorkerReceive.Frame(request()), PortalWorkerReceive.PeerClosed))
        val driver = PortalWorkerDriver(transport) { _, _, _ ->
            throw InvocationTargetException(IllegalArgumentException("guest boom"))
        }

        driver.run()

        assertEquals(1, transport.sent.size)
        assertEquals(PortalKind.Error, transport.sent.single().kind)
        assertEquals(
            "guest boom",
            transport.sent
            .single()
            .payload
            .copyToByteArray()
            .decodeToString(),
        )
    }

    private fun request(capabilities: List<Capability.ReadFd> = emptyList()) = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, PortalPayload(ByteArray(0)), capabilities.size)

    private fun openCapability(path: Path): Capability.ReadFd =
        NativeArena.ofConfined().use { arena ->
            val raw = LinuxNative.fileSystem
                .open(
                    arena.allocateFrom(path.toAbsolutePath().toString()),
                    OpenFlags(NativeConstants.O_RDONLY or NativeConstants.O_CLOEXEC),
                ).getOrThrow("open worker test capability")
            Capability.readFd(FileDescriptor.adopt(raw.toInt(), FileDescriptorRole.Granted))
        }

    private class FakeTransport(
        receives: List<PortalWorkerReceive>,
        private val sendFailure: IOException? = null,
        private val closeFailure: IOException? = null,
    ) : PortalWorkerTransport {
        private val remaining = ArrayDeque(receives)
        val sent = mutableListOf<PortalFrame>()
        var sendAttempts = 0
        var receiveAttempts = 0
        var closed = false

        override fun receive(): PortalWorkerReceive {
            receiveAttempts++
            return remaining.removeFirstOrNull() ?: PortalWorkerReceive.PeerClosed
        }

        override fun send(frame: PortalFrame) {
            sendAttempts++
            sendFailure?.let { throw it }
            sent += frame
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
