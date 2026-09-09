package io.mazewall.enforcer.supervisor

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Private serial protocol. Every variable-sized allocation is bounded before allocation. */
internal object SocketHelperProtocol {
    const val MAX_ADDRESS_BYTES = 128
    const val MAX_ERRNO = 4095

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
        }
        output.flush()
    }

    fun readOperation(input: DataInputStream): SocketOperation =
        when (val tag = input.readUnsignedByte()) {
        1 -> {
            val bytes = readBytes(input)
            if (bytes.size < 2) throw IOException("Short socket address")
            SocketOperation.Connect(bytes)
        }
        2 -> SocketOperation.Accept(input.readInt())
        else -> throw IOException("Invalid helper operation $tag")
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
