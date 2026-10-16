package io.mazewall.profiler.tierE.ringbuf

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

/**
 * Fixed-input decoder diagnostic. It is intentionally separate from live ring draining:
 * component timings are diagnostic and must not be subtracted from end-to-end phase totals.
 */
internal object TierERingDecoderMicrobenchmark {
    private const val SINK_SIZE = 1024
    private const val OFF_KTIME = 0L
    private const val OFF_TGID = 8L
    private const val OFF_TID = 12L
    private const val OFF_SYSCALL_NR = 16L
    private const val OFF_FLAGS = 20L
    private const val OFF_INVOCATION_ID = 24L
    private const val OFF_SEQUENCE = 32L
    private const val OFF_ARGS = 40L
    private val INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    private val LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    private val eventSink = arrayOfNulls<SyscallInvocationEvent>(SINK_SIZE)
    private val scalarSink = LongArray(SINK_SIZE)
    private val fixedArguments = java.util.List.of(1000uL, 1001uL, 1002uL, 1003uL, 1004uL, 1005uL)

    internal enum class Variant(
        val wireName: String,
    ) {
        DIRECT("direct"),
        SCALAR_READS("scalar-reads"),
        EVENT_CONSTRUCTION("event-construction"),
        ;

        companion object {
            fun fromWireName(value: String): Variant = entries.singleOrNull { it.wireName == value } ?: error("unknown decoder variant: $value")
        }
    }

    internal data class Sample(
        val operations: Int,
        val elapsedNanos: Long,
        val checksum: Long,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..4) { "usage: <variant> [warmups] [samples] [iterations]" }
        val variant = Variant.fromWireName(args[0])
        val warmups = args.getOrElse(1) { "3" }.toInt()
        val samples = args.getOrElse(2) { "10" }.toInt()
        val iterations = args.getOrElse(3) { "1000000" }.toInt()
        val fork = System.getProperty("mazewall.tier-e.decoder-microbenchmark.fork", "0").toInt()
        require(warmups >= 0 && samples > 0 && iterations > 0) { "warmups must be non-negative; samples and iterations must be positive" }

        repeat(warmups) { runSample(variant, iterations) }
        val measured = List(samples) { index ->
            val sample = runSample(variant, iterations)
            println(
                "{\"kind\":\"sample\",\"fork\":$fork,\"variant\":\"${variant.wireName}\",\"index\":$index," +
                    "\"operations\":${sample.operations},\"elapsed_ns\":${sample.elapsedNanos},\"checksum\":${sample.checksum}}",
            )
            sample
        }
        val median = measured.map { it.elapsedNanos }.sorted()[measured.size / 2]
        println(
            "{\"kind\":\"summary\",\"fork\":$fork,\"variant\":\"${variant.wireName}\",\"samples\":$samples," +
                "\"operations\":$iterations,\"median_ns\":$median,\"median_ns_per_operation\":${median.toDouble() / iterations}}",
        )
    }

    internal fun runSample(
        variant: Variant,
        iterations: Int,
    ): Sample {
        require(iterations > 0) { "iterations must be positive" }
        Arena.ofConfined().use { arena ->
            val segment = fixedRecord(arena)
            var checksum = 0L
            val started = System.nanoTime()
            when (variant) {
                Variant.DIRECT -> repeat(iterations) { index ->
                    val event = SyscallInvocationEvent.fromSegment(segment)
                    eventSink[index and (SINK_SIZE - 1)] = event
                    checksum = mix(checksum, checksum(event))
                }
                Variant.SCALAR_READS -> repeat(iterations) { index ->
                    val scalarChecksum = scalarChecksum(segment)
                    scalarSink[index and (SINK_SIZE - 1)] = scalarChecksum
                    checksum = mix(checksum, scalarChecksum)
                }
                Variant.EVENT_CONSTRUCTION -> repeat(iterations) { index ->
                    val event = fixedEvent()
                    eventSink[index and (SINK_SIZE - 1)] = event
                    checksum = mix(checksum, checksum(event))
                }
            }
            return Sample(iterations, System.nanoTime() - started, checksum)
        }
    }

    private fun fixedRecord(arena: Arena): MemorySegment =
        arena.allocate(SyscallInvocationEvent.SIZE_BYTES.toLong()).also { segment ->
            segment.set(LONG_LE, OFF_KTIME, 101)
            segment.set(INT_LE, OFF_TGID, 17)
            segment.set(INT_LE, OFF_TID, 23)
            segment.set(INT_LE, OFF_SYSCALL_NR, 257)
            segment.set(INT_LE, OFF_FLAGS, 0)
            segment.set(LONG_LE, OFF_INVOCATION_ID, 202)
            segment.set(LONG_LE, OFF_SEQUENCE, 303)
            repeat(SyscallInvocationEvent.ARGUMENT_COUNT) { index ->
                segment.set(LONG_LE, OFF_ARGS + index * Long.SIZE_BYTES, 1000L + index)
            }
        }

    private fun scalarChecksum(segment: MemorySegment): Long {
        var checksum = segment.get(LONG_LE, OFF_KTIME)
        checksum = checksum xor segment.get(INT_LE, OFF_TGID).toLong()
        checksum = checksum xor segment.get(INT_LE, OFF_TID).toLong()
        checksum = checksum xor segment.get(INT_LE, OFF_SYSCALL_NR).toLong()
        checksum = checksum xor segment.get(INT_LE, OFF_FLAGS).toLong()
        checksum = checksum xor segment.get(LONG_LE, OFF_INVOCATION_ID)
        checksum = checksum xor segment.get(LONG_LE, OFF_SEQUENCE)
        repeat(SyscallInvocationEvent.ARGUMENT_COUNT) { index ->
            checksum = checksum xor segment.get(LONG_LE, OFF_ARGS + index * Long.SIZE_BYTES)
        }
        return checksum
    }

    private fun fixedEvent(): SyscallInvocationEvent =
        SyscallInvocationEvent(
            ktimeNs = 101u,
            tgid = 17u,
            tid = 23u,
            syscallNr = 257,
            flags = 0,
            invocationId = 202u,
            sequence = 303u,
            args = fixedArguments,
        )

    private fun checksum(event: SyscallInvocationEvent): Long =
        event.ktimeNs.toLong() xor
            event.tgid.toLong() xor
            event.tid.toLong() xor
            event.syscallNr.toLong() xor
            event.flags.toLong() xor
            event.invocationId.toLong() xor
            event.sequence.toLong() xor
            event.args.fold(0L) { checksum, argument -> checksum xor argument.toLong() }

    private fun mix(
        current: Long,
        value: Long,
    ): Long = current * 31L + value
}
