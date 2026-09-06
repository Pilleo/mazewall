package io.mazewall.profiler.tierE.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Loads the BTF type blob generated from the narrow invocation-state C declaration. */
internal object InvocationStateBtf {
    private const val RESOURCE: String = "/btf/invocation_state.btf"

    fun load(): ByteArray =
        requireNotNull(InvocationStateBtf::class.java.getResourceAsStream(RESOURCE)) {
        "missing generated BTF resource $RESOURCE; run :profiler:processResources"
    }.use { it.readBytes() }

    fun invocationStateTypeId(): Int = findStructTypeId(load(), "mazewall_invocation_state")

    fun taskStorageKeyTypeId(): Int = findTypeId(load(), "int", BTF_KIND_INT)

    fun findStructTypeId(
        bytes: ByteArray,
        expectedName: String,
    ): Int = findTypeId(bytes, expectedName, BTF_KIND_STRUCT)

    private fun findTypeId(
        bytes: ByteArray,
        expectedName: String,
        expectedKind: Int,
    ): Int {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.limit() >= BTF_HEADER_SIZE) { "BTF header is truncated" }
        require(buffer.getShort(0).toInt() and 0xFFFF == BTF_MAGIC) { "unexpected BTF magic" }
        val headerLength = buffer.getInt(4)
        val typeOffset = buffer.getInt(8)
        val typeLength = buffer.getInt(12)
        val stringOffset = buffer.getInt(16)
        val stringLength = buffer.getInt(20)
        val typeEnd = headerLength + typeOffset + typeLength
        val strings = headerLength + stringOffset
        require(headerLength >= BTF_HEADER_SIZE && typeEnd <= buffer.limit() && strings + stringLength <= buffer.limit()) { "invalid BTF sections" }

        var cursor = headerLength + typeOffset
        var typeId = 1
        while (cursor < typeEnd) {
            require(cursor + BTF_TYPE_SIZE <= typeEnd) { "truncated BTF type" }
            val nameOffset = buffer.getInt(cursor)
            val info = buffer.getInt(cursor + 4)
            val kind = (info ushr 24) and 0x1F
            val vlen = info and 0xFFFF
            if (kind == expectedKind && stringAt(buffer, strings, stringLength, nameOffset) == expectedName) return typeId
            cursor += BTF_TYPE_SIZE + payloadSize(kind, vlen)
            typeId++
        }
        error("BTF type $expectedName was not found")
    }

    private fun payloadSize(
        kind: Int,
        vlen: Int,
    ): Int =
        when (kind) {
        1, 14, 17 -> 4
        3 -> 12
        4, 5 -> vlen * 12
        6 -> vlen * 8
        13 -> vlen * 8
        15, 19 -> vlen * 12
        else -> 0
    }

    private fun stringAt(
        buffer: ByteBuffer,
        start: Int,
        length: Int,
        offset: Int,
    ): String {
        require(offset in 0 until length) { "invalid BTF string offset" }
        val end = generateSequence(start + offset) { index -> (index + 1).takeIf { it < start + length && buffer.get(it) != 0.toByte() } }.last()
        return ByteArray(end - (start + offset) + 1) { index -> buffer.get(start + offset + index) }.toString(Charsets.UTF_8)
    }

    private const val BTF_MAGIC: Int = 0xEB9F
    private const val BTF_HEADER_SIZE: Int = 24
    private const val BTF_TYPE_SIZE: Int = 12
    private const val BTF_KIND_INT: Int = 1
    private const val BTF_KIND_STRUCT: Int = 4
}
