package io.mazewall.profiler.internal

import io.mazewall.profiler.api.ConnectResolution
import io.mazewall.profiler.api.NetworkEndpoint
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HexFormat

/** Heap-only Linux UAPI sockaddr codec; numeric formatting never resolves a hostname. */
internal object SockaddrDecoder {
    const val MAX_BYTES = 128
    private const val INET_BYTES = 16
    private const val INET6_BYTES = 28
    private const val UNIX_BYTES = 110
    private const val IPV6_ADDRESS_OFFSET = 8
    private const val IPV6_SCOPE_OFFSET = 24
    private const val IPV4_ADDRESS_OFFSET = 4
    private const val IPV4_ADDRESS_END = 8
    private const val AF_INET = 2
    private const val AF_INET6 = 10
    private const val AF_UNIX = 1
    private const val FAMILY_BYTES = 2
    private const val FAMILY_MASK = 0xffff
    private const val BYTE_MASK = 0xff

    fun decode(
        bytes: ByteArray,
        order: ByteOrder = ByteOrder.nativeOrder(),
    ): ConnectResolution {
        if (bytes.size !in FAMILY_BYTES..MAX_BYTES) return failed(ConnectResolution.Reason.INVALID_ARGUMENTS, "invalid sockaddr size")
        val buffer = ByteBuffer.wrap(bytes).order(order)
        val family = buffer.short.toInt() and FAMILY_MASK
        return when (family) {
            AF_INET -> if (bytes.size < INET_BYTES) truncated() else endpoint(InetAddress.getByAddress(bytes.copyOfRange(IPV4_ADDRESS_OFFSET, IPV4_ADDRESS_END)).hostAddress, port(bytes))
            AF_INET6 -> if (bytes.size < INET6_BYTES) {
                truncated()
            } else {
                val host = InetAddress.getByAddress(bytes.copyOfRange(IPV6_ADDRESS_OFFSET, IPV6_SCOPE_OFFSET)).hostAddress
                val scope = buffer.getInt(IPV6_SCOPE_OFFSET)
                endpoint(if (scope == 0) host else "$host%${Integer.toUnsignedString(scope)}", port(bytes))
            }
            AF_UNIX -> unix(bytes)
            else -> failed(ConnectResolution.Reason.UNSUPPORTED_FAMILY, "unsupported CONNECT address family $family")
        }
    }

    private fun unix(bytes: ByteArray): ConnectResolution {
        if (bytes.size !in (FAMILY_BYTES + 1)..UNIX_BYTES) return failed(ConnectResolution.Reason.INVALID_ARGUMENTS, "invalid Unix socket address size")
        val path = bytes.copyOfRange(FAMILY_BYTES, bytes.size)
        if (path[0] == 0.toByte()) return endpoint("unix-abstract:${HexFormat.of().formatHex(path, 1, path.size)}", null)
        val end = path.indexOf(0).takeIf { it >= 0 } ?: path.size
        return try {
            endpoint("unix:${decodeTraceUtf8(path.copyOf(end))}", null)
        } catch (_: java.nio.charset.CharacterCodingException) {
            failed(ConnectResolution.Reason.INVALID_ARGUMENTS, "Unix socket pathname is not valid UTF-8")
        }
    }

    private fun port(bytes: ByteArray): Int = ((bytes[FAMILY_BYTES].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or (bytes[FAMILY_BYTES + 1].toInt() and BYTE_MASK)

    private fun endpoint(
        host: String,
        port: Int?,
    ): ConnectResolution = ConnectResolution.Resolved(NetworkEndpoint(host, port))

    private fun truncated(): ConnectResolution = failed(ConnectResolution.Reason.TRUNCATED, "truncated CONNECT socket address")

    private fun failed(
        reason: ConnectResolution.Reason,
        detail: String,
    ): ConnectResolution = ConnectResolution.Failed(reason, detail)
}
