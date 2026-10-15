package io.mazewall.profiler.internal.engine

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.profiler.api.ConnectResolution
import io.mazewall.profiler.api.NetworkEndpoint
import io.mazewall.profiler.internal.SockaddrDecoder
import org.junit.jupiter.api.Test
import java.nio.ByteOrder
import java.util.HexFormat
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectAddressResolverTest {
    @Test
    fun `Linux address families use native family and scope but network-order ports`() {
        val cases = listOf(
            Triple("02001f907f0000010000000000000000", ByteOrder.LITTLE_ENDIAN, NetworkEndpoint("127.0.0.1", 8080)),
            Triple("00021f907f0000010000000000000000", ByteOrder.BIG_ENDIAN, NetworkEndpoint("127.0.0.1", 8080)),
            Triple("0a0001bb0000000020010db800000000000000000000000100000000", ByteOrder.LITTLE_ENDIAN, NetworkEndpoint("2001:db8:0:0:0:0:0:1", 443)),
            Triple("000a01bb00000000fe80000000000000000000000000000100000003", ByteOrder.BIG_ENDIAN, NetworkEndpoint("fe80:0:0:0:0:0:0:1%3", 443)),
            Triple("0a001f900000000000000000000000000000ffff7f00000100000000", ByteOrder.LITTLE_ENDIAN, NetworkEndpoint("127.0.0.1", 8080)),
            Triple("01002f746d702f736f636b00", ByteOrder.LITTLE_ENDIAN, NetworkEndpoint("unix:/tmp/sock")),
            Triple("010000ff0001", ByteOrder.LITTLE_ENDIAN, NetworkEndpoint("unix-abstract:ff0001")),
        )
        for ((hex, order, expected) in cases) {
            assertEquals(expected, assertIs<ConnectResolution.Resolved>(SockaddrDecoder.decode(HexFormat.of().parseHex(hex), order)).endpoint)
        }
    }

    @Test
    fun `short unknown and malformed addresses remain unresolved`() {
        for ((bytes, reason) in listOf(
            byteArrayOf() to ConnectResolution.Reason.INVALID_ARGUMENTS,
            byteArrayOf(2, 0, 0, 80) to ConnectResolution.Reason.TRUNCATED,
            byteArrayOf(10, 0, 0, 80) to ConnectResolution.Reason.TRUNCATED,
            byteArrayOf(42, 0) to ConnectResolution.Reason.UNSUPPORTED_FAMILY,
            byteArrayOf(1, 0, 0x80.toByte()) to ConnectResolution.Reason.INVALID_ARGUMENTS,
            ByteArray(129) to ConnectResolution.Reason.INVALID_ARGUMENTS,
        )) {
            assertEquals(reason, assertIs<ConnectResolution.Failed>(SockaddrDecoder.decode(bytes, ByteOrder.LITTLE_ENDIAN)).reason)
        }
    }

    @Test
    fun `missing invalid and oversized registers never cause a remote read`() {
        val reader = object : ProfilerMemoryReader {
            context(arena: NativeArena) override fun readBytes(
                tid: Tid,
                remoteAddr: Long,
                len: Int,
            ): ByteArray? = error("invalid request reached remote reader")
        }
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val resolver = ConnectAddressResolver(reader)
                for (args in listOf(emptyList(), listOf(3L, 0, 16), listOf(3L, 4096, -1), listOf(3L, 4096, 129), listOf(3L, 4096, 0))) {
                    assertEquals(ConnectResolution.Reason.INVALID_ARGUMENTS, assertIs<ConnectResolution.Failed>(resolver.resolve(Tid(7), "CONNECT", args)).reason)
                }
                assertEquals(ConnectResolution.NotApplicable, resolver.resolve(Tid(7), "GETPID", emptyList()))
            }
        }
    }

    @Test
    fun `memory permission failures retain errno diagnostics and partial reads stay incomplete`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                for (errno in listOf(1, 13)) {
                    val reader = object : ProfilerMemoryReader {
                        context(arena: NativeArena) override fun readBytes(
                            tid: Tid,
                            remoteAddr: Long,
                            len: Int,
                        ): ByteArray? = throw IllegalStateException("memory read errno=$errno")
                    }
                    val failed = assertIs<ConnectResolution.Failed>(ConnectAddressResolver(reader).resolve(Tid(7), "CONNECT", listOf(3, 4096, 16)))
                    assertEquals(ConnectResolution.Reason.MEMORY_READ_FAILED, failed.reason)
                    assertTrue(failed.detail.contains("errno=$errno"))
                }
                val partial = object : ProfilerMemoryReader {
                    context(arena: NativeArena) override fun readBytes(
                        tid: Tid,
                        remoteAddr: Long,
                        len: Int,
                    ): ByteArray = ByteArray(len - 1)
                }
                assertEquals(ConnectResolution.Reason.TRUNCATED, assertIs<ConnectResolution.Failed>(ConnectAddressResolver(partial).resolve(Tid(7), "CONNECT", listOf(3, 4096, 16))).reason)
                val cancellation = CancellationException("read cancelled")
                val cancelled = object : ProfilerMemoryReader {
                    context(arena: NativeArena) override fun readBytes(
                        tid: Tid,
                        remoteAddr: Long,
                        len: Int,
                    ): ByteArray? = throw cancellation
                }
                assertSame(cancellation, assertFailsWith<CancellationException> { ConnectAddressResolver(cancelled).resolve(Tid(7), "CONNECT", listOf(3, 4096, 16)) })
            }
        }
    }
}
