package io.mazewall.profiler.tierE.ringbuf

import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.syscall.*
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.lang.reflect.Proxy

/**
 * Deterministic mapped-ring drain diagnostic. Producer publication is deliberately outside each
 * timed interval; measurements cover only complete owned drain calls over test-owned mappings.
 */
internal object TierERingMappedDrainMicrobenchmark {
    private const val PAGE_SIZE = 4096L
    private const val CAPACITY = 4096L
    private val payload = ByteArray(SyscallInvocationEvent.SIZE_BYTES)

    internal enum class Variant(
        val wireName: String,
        val recordsPerDrain: Int,
    ) {
        EMPTY("empty", 0),
        SINGLE("single", 1),
        BATCH_16("batch-16", 16),
        ;

        companion object {
            fun fromWireName(value: String): Variant = entries.singleOrNull { it.wireName == value } ?: error("unknown mapped-drain variant: $value")
        }
    }

    internal data class Sample(
        val drains: Int,
        val records: Long,
        val elapsedNanos: Long,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..4) { "usage: <empty|single|batch-16> [warmups] [samples] [drains]" }
        val variant = Variant.fromWireName(args[0])
        val warmups = args.getOrElse(1) { "3" }.toInt()
        val samples = args.getOrElse(2) { "10" }.toInt()
        val drains = args.getOrElse(3) { "100000" }.toInt()
        val fork = System.getProperty("mazewall.tier-e.mapped-drain-microbenchmark.fork", "0").toInt()
        require(warmups >= 0 && samples > 0 && drains > 0) { "warmups must be non-negative; samples and drains must be positive" }

        repeat(warmups) { runSample(variant, drains) }
        val measured = List(samples) { index ->
            val sample = runSample(variant, drains)
            println(
                "{\"kind\":\"sample\",\"fork\":$fork,\"variant\":\"${variant.wireName}\",\"index\":$index," +
                    "\"drains\":${sample.drains},\"records\":${sample.records},\"elapsed_ns\":${sample.elapsedNanos}}",
            )
            sample
        }
        val median = measured.map { it.elapsedNanos }.sorted()[measured.size / 2]
        println(
            "{\"kind\":\"summary\",\"fork\":$fork,\"variant\":\"${variant.wireName}\",\"samples\":$samples," +
                "\"drains\":$drains,\"median_ns\":$median,\"median_ns_per_drain\":${median.toDouble() / drains}}",
        )
    }

    internal fun runSample(
        variant: Variant,
        drains: Int,
    ): Sample {
        require(drains > 0) { "drains must be positive" }
        FakeMappings().use { mappings ->
            val reader = BpfRingBufferReader(mappings.native, 7, CAPACITY, SyscallInvocationEvent.SIZE_BYTES)
            try {
                var writerPosition = 0L
                var elapsedNanos = 0L
                var records = 0L
                repeat(drains) {
                    repeat(variant.recordsPerDrain) {
                        mappings.writeRecord(writerPosition, SyscallInvocationEvent.SIZE_BYTES, payload)
                        writerPosition += BpfRingBufferReader.alignedRecordSize(SyscallInvocationEvent.SIZE_BYTES)
                    }
                    mappings.setProducerPosition(writerPosition)
                    val started = System.nanoTime()
                    val drained = reader.drainDecoded(SyscallInvocationEvent::fromSegment)
                    elapsedNanos += System.nanoTime() - started
                    records += drained.size
                }
                return Sample(drains, records, elapsedNanos)
            } finally {
                reader.close()
            }
        }
    }

    private class FakeMappings : AutoCloseable {
        private val consumerArena = Arena.ofShared()
        private val producerArena = Arena.ofShared()
        private val consumer = consumerArena.allocate(PAGE_SIZE, 8)
        private val producer = producerArena.allocate(PAGE_SIZE + CAPACITY * 2, 8)
        private var mappings = 0
        val native: RawSyscallOperations = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(RawSyscallOperations::class.java),
        ) { _, method, args ->
            check(method.name == "syscall")
            when (args[0] as Long) {
                Syscall.MMAP.numberFor(Arch.current()).toLong() ->
                    SyscallResult.Success<Long, SyscallHandledState.Unhandled>(
                        if (mappings++ == 0) consumer.address() else producer.address(),
                    )
                Syscall.MUNMAP.numberFor(Arch.current()).toLong() ->
                    SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
                else -> error("unexpected syscall ${args[0]}")
            }
        } as RawSyscallOperations

        fun setProducerPosition(position: Long) = LONG_HANDLE.setRelease(producer, 0L, position)

        fun writeRecord(
            position: Long,
            payloadSize: Int,
            bytes: ByteArray,
        ) {
            val offset = PAGE_SIZE + (position and (CAPACITY - 1))
            INT_HANDLE.setRelease(producer, offset, payloadSize)
            bytes.forEachIndexed { index, value -> producer.set(ValueLayout.JAVA_BYTE, offset + 8 + index, value) }
        }

        override fun close() {
            consumerArena.close()
            producerArena.close()
        }
    }

    private val LONG_HANDLE = ValueLayout.JAVA_LONG.varHandle()
    private val INT_HANDLE = ValueLayout.JAVA_INT.varHandle()
}
