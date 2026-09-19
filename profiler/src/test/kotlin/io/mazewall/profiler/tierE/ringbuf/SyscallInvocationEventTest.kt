package io.mazewall.profiler.tierE.ringbuf
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.lang.foreign.Arena
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SyscallInvocationEventTest {
    @Test
    fun `decodes the fixed little endian syscall invocation wire record`() {
        val bytes = ByteArray(SyscallInvocationEvent.SIZE_BYTES)
        putLong(bytes, 0, 0x0102_0304_0506_0708L)
        putInt(bytes, 8, 17)
        putInt(bytes, 12, 23)
        putInt(bytes, 16, 257)
        putInt(bytes, 20, SyscallInvocationEvent.FLAG_VTHREAD_EXPERIMENTAL)
        putLong(bytes, 24, 101)
        putLong(bytes, 32, 202)
        (0 until SyscallInvocationEvent.ARGUMENT_COUNT).forEach { putLong(bytes, 40 + it * 8, it.toLong() + 1000) }

        assertEquals(
            SyscallInvocationEvent(
                ktimeNs = 0x0102_0304_0506_0708u,
                tgid = 17u,
                tid = 23u,
                syscallNr = 257,
                flags = SyscallInvocationEvent.FLAG_VTHREAD_EXPERIMENTAL,
                invocationId = 101u,
                sequence = 202u,
                args = List(SyscallInvocationEvent.ARGUMENT_COUNT) { it.toULong() + 1000u },
            ),
            SyscallInvocationEvent.fromBytes(bytes),
        )
    }

    @Test
    fun `rejects short and malformed argument records`() {
        assertFailsWith<IllegalArgumentException> { SyscallInvocationEvent.fromBytes(ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> {
            SyscallInvocationEvent(
                ktimeNs = 0u,
                tgid = 0u,
                tid = 0u,
                syscallNr = 0,
                flags = 0,
                invocationId = 0u,
                sequence = 0u,
                args = emptyList(),
            )
        }
    }

    @Test
    fun `direct ring decoder argument construction controls preserve immutable event content`() {
        val bytes = ByteArray(SyscallInvocationEvent.SIZE_BYTES)
        putLong(bytes, 0, 101)
        putInt(bytes, 8, 17)
        putInt(bytes, 12, 23)
        putInt(bytes, 16, 257)
        putLong(bytes, 24, 101)
        putLong(bytes, 32, 202)
        (0 until SyscallInvocationEvent.ARGUMENT_COUNT).forEach { putLong(bytes, 40 + it * 8, it.toLong() + 1000) }

        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(bytes.size.toLong())
            segment.asByteBuffer().put(bytes)
            val immutableFactory = SyscallInvocationEvent.fromSegment(segment)
            val lambdaCopy = SyscallInvocationEvent.fromSegment(segment, legacyArgumentListConstruction = true)

            assertEquals(immutableFactory, lambdaCopy)
            assertEquals(immutableFactory.args, lambdaCopy.args)
            assertContentEquals(longArrayOf(1000, 1001, 1002, 1003, 1004, 1005), immutableFactory.takeOwnedArguments())
            assertEquals(List(SyscallInvocationEvent.ARGUMENT_COUNT) { it.toULong() + 1000u }, immutableFactory.args)
            assertSame(immutableFactory.args, immutableFactory.args)
            assertFailsWith<IllegalStateException> { immutableFactory.takeOwnedArguments() }
        }
    }

    private fun putInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        repeat(Int.SIZE_BYTES) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putLong(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        repeat(Long.SIZE_BYTES) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }
}
