package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.writeByte
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class JvmVerdictExchangeTest {
    @Test
    fun `write retries EINTR and short writes without changing the request bytes`() {
        val fixture = SupervisorBoundaryFixture()
        val written = mutableListOf<Byte>()
        var calls = 0
        fixture.engine.memory.onWrite = { _, buffer, size ->
            calls++
            if (calls == 1) {
                SupervisorBoundaryFixture.failure(4)
            } else {
                val count = minOf(size, 7).toInt()
                val bytes = ByteArray(count)
                ManagedSegment.copy(buffer, 0, bytes, 0, count)
                written.addAll(bytes.toList())
                SupervisorBoundaryFixture.success(count.toLong())
            }
        }
        NativeArena.ofConfined().use { arena ->
            val exchange = JvmVerdictExchange(fixture.engine, fixture.socket, fixture.sockets)
            assertTrue(with(arena) { exchange.send(fixture.context(arena).request) })
        }
        assertTrue(calls > 2)
        // Request ID is the first network-order long in the unchanged wire format.
        assertEquals(listOf<Byte>(0, 0, 0, 0, 0, 0, 0, 42), written.take(8))
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `read tolerates EINTR in poll and read and assembles a fragmented frame`() {
        val fixture = SupervisorBoundaryFixture()
        val payload = frame()
        var offset = 0
        var reads = 0
        var polls = 0
        fixture.engine.onPoll = { _, _, _ ->
            polls++
            if (polls <= 5) SupervisorBoundaryFixture.failure(4) else SupervisorBoundaryFixture.success(1)
        }
        fixture.engine.memory.onRead = { _, buffer, size ->
            reads++
            if (reads == 1) {
                SupervisorBoundaryFixture.failure(4)
            } else {
                val count = minOf(size.toInt(), 23)
                ManagedSegment.copy(payload, offset, buffer, 0, count)
                offset += count
                SupervisorBoundaryFixture.success(count.toLong())
            }
        }
        assertEquals(JvmExchangeResult.Verdict(SupervisorRoute.Continue, null), receive(fixture))
        assertEquals(4116, offset)
        assertTrue(polls > reads)
        assertEquals(0, fixture.sockets.closeCalledCount.get())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 20, 4115])
    fun `EOF and short frames terminate transport without a verdict`(length: Int) {
        val fixture = SupervisorBoundaryFixture()
        val bytes = frame()
        var available = length
        fixture.engine.memory.onRead = { _, buffer, _ ->
            val count = available
            if (count > 0) ManagedSegment.copy(bytes, 0, buffer, 0, count)
            available = 0
            SupervisorBoundaryFixture.success(count.toLong())
        }
        assertEquals(JvmExchangeResult.TransportFailed(5), receive(fixture))
        assertEquals(1, fixture.sockets.closeCalledCount.get())
        assertEquals(0, fixture.replies.size)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 4097, Int.MAX_VALUE])
    fun `untrusted oversized or negative path lengths are never capped into valid paths`(length: Int) {
        val fixture = SupervisorBoundaryFixture()
        fixture.pathLength = length
        assertInstanceOf(JvmExchangeResult.Malformed::class.java, receive(fixture))
        assertEquals(1, fixture.sockets.closeCalledCount.get())
    }

    @Test
    fun `invalid UTF-8 exec path fails closed`() {
        val fixture = SupervisorBoundaryFixture()
        val read = fixture.engine.memory.onRead
        fixture.pathLength = 1
        fixture.engine.memory.onRead = { fd, buffer, size ->
            read(fd, buffer, size).also { buffer.writeByte(20, 0xff.toByte()) }
        }
        assertInstanceOf(JvmExchangeResult.Malformed::class.java, receive(fixture))
    }

    @Test
    fun `mismatched notification identity closes the control socket`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.responseId = 99
        assertEquals(JvmExchangeResult.Malformed("notification ID mismatch"), receive(fixture))
        assertEquals(1, fixture.sockets.closeCalledCount.get())
    }

    @Test
    fun `timeout after a partial frame closes the socket without a reply`() {
        val fixture = SupervisorBoundaryFixture()
        var polls = 0
        fixture.engine.onPoll = { _, _, _ -> SupervisorBoundaryFixture.success(if (++polls == 1) 1 else 0) }
        fixture.engine.memory.onRead = { _, buffer, _ ->
            buffer.writeByte(0, 42)
            SupervisorBoundaryFixture.success(1)
        }
        assertEquals(JvmExchangeResult.Timeout, receive(fixture))
        assertEquals(1, fixture.sockets.closeCalledCount.get())
        assertEquals(0, fixture.replies.size)
    }

    @Test
    fun `interruption is preserved and never starts an unbounded read`() {
        val fixture = SupervisorBoundaryFixture()
        Thread.currentThread().interrupt()
        try {
            assertEquals(JvmExchangeResult.TransportFailed(NativeConstants.EIO), receive(fixture))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, fixture.readCount)
        } finally {
            Thread.interrupted()
        }
    }

    private fun receive(fixture: SupervisorBoundaryFixture): JvmExchangeResult =
        NativeArena.ofConfined().use { arena ->
            with(arena) { JvmVerdictExchange(fixture.engine, fixture.socket, fixture.sockets).receive(fixture.context(arena).request) }
        }

    private fun frame(): ByteArray =
        NativeArena.ofConfined().use { arena ->
            val buffer = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
            SupervisorResponseSegment.of(buffer).apply {
                setId(42)
                setDecision(1)
                setErrorNr(0)
            }
            ByteArray(4116).also { ManagedSegment.copy(buffer, 0, it, 0, it.size) }
        }
}
