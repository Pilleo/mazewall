package io.mazewall.profiler.tierE.ringbuf
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpfRingBufferReaderTest {
    @Test
    fun `record cursor includes header and rounds to eight bytes`() {
        assertEquals(96L, BpfRingBufferReader.alignedRecordSize(SyscallInvocationEvent.SIZE_BYTES))
        assertEquals(16L, BpfRingBufferReader.alignedRecordSize(1))
    }

    @Test
    fun `two mapping layout keeps metadata writable and data read only`() {
        assertEquals(3, BpfRingBufferReader.METADATA_MAPPING_PROTECTION)
        assertEquals(1, BpfRingBufferReader.DATA_MAPPING_PROTECTION)
    }

    @Test
    fun `busy and discard records are distinct from ready records`() {
        assertEquals(BpfRingBufferRecordKind.READY, BpfRingBufferReader.classify(8))
        assertEquals(BpfRingBufferRecordKind.BUSY, BpfRingBufferReader.classify(Int.MIN_VALUE or 8))
        assertEquals(BpfRingBufferRecordKind.DISCARDED, BpfRingBufferReader.classify((1 shl 30) or 8))
    }

    @Test
    fun `concurrent producer reservations preserve acquire release cursor order`() {
        val position = AtomicLong(0)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(4)
        repeat(4) {
            Thread {
                start.await()
                repeat(100) { position.addAndGet(BpfRingBufferReader.nextPosition(0, 8)) }
                finished.countDown()
            }.start()
        }
        start.countDown()
        finished.await()

        assertEquals(4L * 100L * 16L, position.get())
        assertEquals(128L, BpfRingBufferReader.nextPosition(112, 8))
        assertTrue(BpfRingBufferReader.nextPosition(128, 8) > 128)
    }

    @Test
    fun `reader handles busy discard wraparound and releases consumer position`() {
        val mappings = FakeMappings()
        val reader = BpfRingBufferReader(mappings.native, 7, CAPACITY, PAYLOAD_SIZE)
        try {
            mappings.writeRecord(0, BpfRingBufferReader.BUSY_BIT or PAYLOAD_SIZE, ByteArray(PAYLOAD_SIZE) { it.toByte() })
            mappings.setProducerPosition(16)
            assertEquals(0, reader.drain().size)
            assertEquals(0L, mappings.consumerPosition())

            mappings.writeRecord(0, PAYLOAD_SIZE, ByteArray(PAYLOAD_SIZE) { it.toByte() })
            assertEquals(listOf(ByteArray(PAYLOAD_SIZE) { it.toByte() }.toList()), reader.drain().map(ByteArray::toList))
            assertEquals(16L, mappings.consumerPosition())

            mappings.setConsumerPosition(240)
            mappings.writeRecord(240, PAYLOAD_SIZE, ByteArray(PAYLOAD_SIZE) { 9 })
            mappings.writeRecord(256, BpfRingBufferReader.DISCARD_BIT or PAYLOAD_SIZE, ByteArray(PAYLOAD_SIZE))
            mappings.setProducerPosition(272)
            assertEquals(listOf(ByteArray(PAYLOAD_SIZE) { 9 }.toList()), reader.drain().map(ByteArray::toList))
            assertEquals(272L, mappings.consumerPosition())
        } finally {
            reader.close()
            mappings.close()
        }
    }

    @Test
    fun `close retains only a failed mapping for a later retry`() {
        val mappings = FakeMappings(failFirstProducerUnmap = true)
        val reader = BpfRingBufferReader(mappings.native, 7, CAPACITY, PAYLOAD_SIZE)
        try {
            assertFailsWith<IllegalStateException> { reader.close() }

            reader.close()

            assertEquals(
                listOf(mappings.producerAddress, mappings.consumerAddress, mappings.producerAddress),
                mappings.unmappedAddresses,
            )
        } finally {
            mappings.close()
        }
    }

    private class FakeMappings(
        private var failFirstProducerUnmap: Boolean = false,
    ) : AutoCloseable {
        private val consumerArena = Arena.ofShared()
        private val producerArena = Arena.ofShared()
        private val consumer = consumerArena.allocate(PAGE_SIZE, 8)
        private val producer = producerArena.allocate(PAGE_SIZE + CAPACITY * 2, 8)
        private var mappings = 0
        val unmappedAddresses = mutableListOf<Long>()
        val consumerAddress: Long get() = consumer.address()
        val producerAddress: Long get() = producer.address()
        val native: RawSyscallOperations = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, method, args ->
            check(method.name == "syscall")
            when (args[0] as Long) {
                Syscall.MMAP.numberFor(Arch.current()).toLong() -> SyscallResult.Success<Long, SyscallHandledState.Unhandled>(
                    if (mappings++ == 0) consumer.address() else producer.address(),
                )
                Syscall.MUNMAP.numberFor(Arch.current()).toLong() -> {
                    val address = (args[1] as NativeArg.LongArg).value
                    unmappedAddresses += address
                    if (failFirstProducerUnmap && address == producer.address()) {
                        failFirstProducerUnmap = false
                        SyscallResult.Error<SyscallHandledState.Unhandled>(22, -1L)
                    } else {
                        SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
                    }
                }
                else -> error("unexpected syscall ${args[0]}")
            }
        } as RawSyscallOperations

        fun setProducerPosition(position: Long) = LONG_HANDLE.setRelease(producer, 0L, position)

        fun setConsumerPosition(position: Long) = LONG_HANDLE.setRelease(consumer, 0L, position)

        fun consumerPosition(): Long = LONG_HANDLE.getAcquire(consumer, 0L) as Long

        fun writeRecord(
            position: Long,
            word: Int,
            payload: ByteArray,
        ) {
            val offset = PAGE_SIZE + (position and (CAPACITY - 1))
            INT_HANDLE.setRelease(producer, offset, word)
            payload.forEachIndexed { index, value -> producer.set(ValueLayout.JAVA_BYTE, offset + 8 + index, value) }
        }

        override fun close() {
            consumerArena.close()
            producerArena.close()
        }
    }

    private companion object {
        const val PAGE_SIZE = 4096L
        const val CAPACITY = 256L
        const val PAYLOAD_SIZE = 8
        val LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle()
        val INT_HANDLE = ValueLayout.JAVA_INT.varHandle()
    }
}
