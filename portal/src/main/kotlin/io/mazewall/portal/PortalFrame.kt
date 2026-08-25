package io.mazewall.portal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire kinds for the process-portal Unix RPC. */
public object PortalKind {
    public const val REQUEST: Byte = 1
    public const val RESPONSE: Byte = 2
    public const val ERROR: Byte = 3
}

public object PortalMethods {
    public const val ECHO: Int = 1
    public const val CHECKSUM: Int = 2

    /** Worker sleeps [payload] milliseconds. Used to test call timeouts. */
    public const val SLEEP: Int = 3

    /** Worker tries `FileInputStream("/etc/passwd")`. Used to test Landlock deny. */
    public const val TRY_OPEN_HOST_PASSWD: Int = 4
}

/**
 * Fixed 24-byte big-endian header plus payload. File descriptors travel in
 * a following `SCM_RIGHTS` burst, never in the payload.
 */
public data class PortalFrame(
    val kind: Byte,
    val requestId: Int,
    val methodId: Int,
    val payload: ByteArray,
    val fdCount: Int,
) {
    init {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} exceeds $MAX_PAYLOAD" }
        require(fdCount in 0..MAX_FDS) { "fdCount $fdCount not in 0..$MAX_FDS" }
        if (kind == PortalKind.RESPONSE || kind == PortalKind.ERROR) {
            require(fdCount == 0) { "worker→broker FDs are forbidden" }
        }
    }

    public fun headerBytes(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(kind)
        buf.put(0)
        buf.putShort(0)
        buf.putInt(requestId)
        buf.putInt(methodId)
        buf.putInt(payload.size)
        buf.put(fdCount.toByte())
        buf.put(0)
        buf.put(0)
        buf.put(0)
        return buf.array()
    }

    public companion object {
        public const val HEADER_SIZE: Int = 24
        public const val MAX_PAYLOAD: Int = 8 * 1024 * 1024
        public const val MAX_FDS: Int = 8
        internal val MAGIC: ByteArray = byteArrayOf('M'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())

        public fun parseHeader(bytes: ByteArray): ParsedHeader {
            require(bytes.size == HEADER_SIZE) { "header must be $HEADER_SIZE bytes" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            require(magic.contentEquals(MAGIC)) { "bad portal magic" }
            val kind = buf.get()
            buf.get()
            buf.short
            val requestId = buf.int
            val methodId = buf.int
            val payloadLen = buf.int
            val fdCount = buf.get().toInt() and 0xff
            require(payloadLen in 0..MAX_PAYLOAD) { "payloadLen $payloadLen" }
            require(fdCount in 0..MAX_FDS) { "fdCount $fdCount" }
            if (kind == PortalKind.RESPONSE || kind == PortalKind.ERROR) {
                require(fdCount == 0) { "worker→broker FDs are forbidden" }
            }
            return ParsedHeader(kind, requestId, methodId, payloadLen, fdCount)
        }
    }

    public data class ParsedHeader(
        val kind: Byte,
        val requestId: Int,
        val methodId: Int,
        val payloadLen: Int,
        val fdCount: Int,
    )
}
