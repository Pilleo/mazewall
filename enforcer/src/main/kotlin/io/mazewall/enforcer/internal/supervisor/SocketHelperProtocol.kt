package io.mazewall.enforcer.internal.supervisor

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private serial protocol. Every variable-sized allocation is bounded before allocation. */
internal object SocketHelperProtocol {
    const val MAX_ADDRESS_BYTES = 128
    const val MAX_ERRNO = 4095
    const val MAX_PATH_BYTES = 4095
    const val OPENED_RESULT = 3
    private const val OPEN_OPERATION = 3

    fun readResult(
        input: DataInputStream,
        receive: () -> SupervisorOwnedFd,
    ): SocketHelperResult =
        when (val tag = input.readUnsignedByte()) {
            0 -> SocketHelperResult.Connected
            1 -> SocketHelperResult.Failed(readBounded(input, 1..MAX_ERRNO, "errno"))
            2 -> {
                val length = readBounded(input, 0..MAX_ADDRESS_BYTES, "address length")
                val bytes = readBytes(input)
                if (bytes.size != length) throw IOException("Inconsistent helper address length")
                SocketHelperResult.Accepted(receive(), bytes, length)
            }
            OPENED_RESULT -> SocketHelperResult.Opened(receive())
            else -> throw IOException("Invalid helper result $tag")
        }

    private fun readBounded(
        input: DataInputStream,
        range: IntRange,
        label: String,
    ): Int {
        val value = input.readInt()
        if (value !in range) throw IOException("Invalid helper $label $value")
        return value
    }

    fun writeOperation(
        output: DataOutputStream,
        operation: SocketOperation,
    ) {
        when (operation) {
            is SocketOperation.Connect -> {
                output.writeByte(1)
                writeBytes(output, operation.address)
            }
            is SocketOperation.Accept -> {
                output.writeByte(2)
                output.writeInt(operation.flags)
            }
            is SocketOperation.OpenAt2 -> {
                output.writeByte(OPEN_OPERATION)
                val path = operation.path.toByteArray(Charsets.UTF_8)
                output.writeInt(path.size)
                output.write(path)
                output.writeLong(operation.flags)
                output.writeLong(operation.mode)
                output.writeLong(operation.resolve)
            }
        }
        output.flush()
    }

    fun readOperation(
        input: DataInputStream,
        tag: Int = input.readUnsignedByte(),
    ): SocketOperation =
        when (tag) {
        1 -> {
            val bytes = readBytes(input)
            if (bytes.size < 2) throw IOException("Short socket address")
            SocketOperation.Connect(bytes)
        }
        2 -> SocketOperation.Accept(input.readInt())
        OPEN_OPERATION -> readOpen(input)
        else -> throw IOException("Invalid helper operation $tag")
    }

    private fun readOpen(input: DataInputStream): SocketOperation.OpenAt2 {
        val length = readBounded(input, 0..MAX_PATH_BYTES, "path length")
        val bytes = ByteArray(length).also(input::readFully)
        val path = Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        if (path.startsWith('/') || '\u0000' in path) throw IOException("Invalid helper relative path")
        return SocketOperation.OpenAt2(path, input.readLong(), input.readLong(), input.readLong())
    }

    fun writeBytes(
        output: DataOutputStream,
        bytes: ByteArray,
    ) {
        require(bytes.size <= MAX_ADDRESS_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    fun readBytes(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length !in 0..MAX_ADDRESS_BYTES) throw IOException("Invalid helper byte count $length")
        return ByteArray(length).also(input::readFully)
    }
}
