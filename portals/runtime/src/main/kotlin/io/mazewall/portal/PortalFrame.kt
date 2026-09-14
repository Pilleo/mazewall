package io.mazewall.portal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire kinds for the process-portal Unix RPC. */
public sealed class PortalKind(
    public val wire: Byte,
) {
    public data object Request : PortalKind(1)

    public data object Response : PortalKind(2)

    public data object Error : PortalKind(3)

    public companion object {
        public fun fromWire(wire: Byte): PortalKind =
            when (wire) {
                Request.wire -> Request
                Response.wire -> Response
                Error.wire -> Error
                else -> throw IllegalArgumentException("unknown portal kind $wire")
            }
    }
}

/** A portal operation with an explicitly owned on-wire method id. */
public sealed class PortalMethod(
    public val wire: Int,
) {
    public data object Echo : PortalMethod(1)

    public data object Checksum : PortalMethod(2)

    public data object Sleep : PortalMethod(3)

    public data object TryOpenHostPasswd : PortalMethod(4)

    /** A stable code-generated service method; builtin ids remain reserved. */
    public data class Generated public constructor(
        public val generatedWire: Int,
    ) : PortalMethod(generatedWire) {
        init {
            require(generatedWire !in FIRST_BUILTIN_METHOD_ID..LAST_BUILTIN_METHOD_ID) {
                "generated portal method id $generatedWire is reserved"
            }
        }
    }

    public companion object {
        private const val FIRST_BUILTIN_METHOD_ID = 1
        private const val LAST_BUILTIN_METHOD_ID = 4

        public fun fromWire(wire: Int): PortalMethod =
            when (wire) {
                Echo.wire -> Echo
                Checksum.wire -> Checksum
                Sleep.wire -> Sleep
                TryOpenHostPasswd.wire -> TryOpenHostPasswd
                else -> Generated(wire)
            }
    }
}

/**
 * Fixed 24-byte big-endian header plus payload. File descriptors travel in
 * a following `SCM_RIGHTS` burst, never in the payload.
 */
public class PortalFrame(
    public val kind: PortalKind,
    public val requestId: Int,
    public val method: PortalMethod,
    public val payload: PortalPayload,
    public val fdCount: Int,
) {
    public constructor(kind: PortalKind, requestId: Int, method: PortalMethod, payload: ByteArray, fdCount: Int) :
        this(kind, requestId, method, PortalPayload(payload), fdCount)

    init {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} exceeds $MAX_PAYLOAD" }
        require(fdCount in 0..MAX_FDS) { "fdCount $fdCount not in 0..$MAX_FDS" }
        if (kind == PortalKind.Response || kind == PortalKind.Error) {
            require(fdCount == 0) { "worker→broker FDs are forbidden" }
        }
    }

    public fun headerBytes(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(kind.wire)
        buf.put(0)
        buf.putShort(0)
        buf.putInt(requestId)
        buf.putInt(method.wire)
        buf.putInt(payload.size)
        buf.put(fdCount.toByte())
        buf.put(0)
        buf.put(0)
        buf.put(0)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean =
        other is PortalFrame &&
            kind == other.kind &&
            requestId == other.requestId &&
            method == other.method &&
            payload == other.payload &&
        fdCount == other.fdCount

    override fun hashCode(): Int = listOf(kind, requestId, method, payload, fdCount).hashCode()

    override fun toString(): String = "PortalFrame(kind=$kind, requestId=$requestId, method=$method, payload=$payload, fdCount=$fdCount)"

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
            val kind = PortalKind.fromWire(buf.get())
            buf.get()
            buf.short
            val requestId = buf.int
            val method = PortalMethod.fromWire(buf.int)
            val payloadLen = buf.int
            val fdCount = buf.get().toInt() and 0xff
            require(payloadLen in 0..MAX_PAYLOAD) { "payloadLen $payloadLen" }
            require(fdCount in 0..MAX_FDS) { "fdCount $fdCount" }
            if (kind == PortalKind.Response || kind == PortalKind.Error) {
                require(fdCount == 0) { "worker→broker FDs are forbidden" }
            }
            return ParsedHeader(kind, requestId, method, payloadLen, fdCount)
        }
    }

    public data class ParsedHeader(
        val kind: PortalKind,
        val requestId: Int,
        val method: PortalMethod,
        val payloadLen: Int,
        val fdCount: Int,
    )
}
