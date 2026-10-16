package io.mazewall.enforcer.internal.supervisor

import java.io.DataInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Pure, bounded decoder for the JVM validation-channel preamble and arguments. */
internal object SupervisorValidationDecoder {
    private const val PROTOCOL_ACK_BYTE = 0xAC
    private const val MAX_ARGUMENTS = 6
    private const val MAX_STRING_BYTES = 4096
    private const val MAX_SOCKET_ADDRESS_BYTES = 256

    fun handshake(ack: Int): SupervisorValidationDecodeResult =
        if (ack == PROTOCOL_ACK_BYTE) {
            SupervisorValidationDecodeResult.HandshakeAccepted
        } else {
            SupervisorValidationDecodeResult.ProtocolViolation("invalid handshake ACK: $ack")
        }

    fun arguments(
        input: DataInputStream,
        argumentCount: Int,
    ): SupervisorValidationDecodeResult {
        if (argumentCount !in 0..MAX_ARGUMENTS) {
            return SupervisorValidationDecodeResult.ProtocolViolation("invalid argument count: $argumentCount")
        }
        return try {
            val values = ArrayList<Any>(argumentCount)
            repeat(argumentCount) {
                when (val tag = input.readUnsignedByte()) {
                    LONG_TAG -> values += input.readLong()
                    STRING_TAG -> values += readUtf8(input, MAX_STRING_BYTES, "string")
                    SOCKET_ADDRESS_TAG -> values += readBytes(input, MAX_SOCKET_ADDRESS_BYTES, "socket address")
                    else -> return SupervisorValidationDecodeResult.ProtocolViolation("unknown argument tag: $tag")
                }
            }
            SupervisorValidationDecodeResult.Arguments(values)
        } catch (failure: IOException) {
            SupervisorValidationDecodeResult.ProtocolViolation("truncated validation arguments: ${failure.message}")
        } catch (failure: IllegalArgumentException) {
            SupervisorValidationDecodeResult.ProtocolViolation(failure.message ?: "invalid validation argument")
        }
    }

    private fun readUtf8(
        input: DataInputStream,
        limit: Int,
        field: String,
    ): String = String(readBytes(input, limit, field), StandardCharsets.UTF_8)

    private fun readBytes(
        input: DataInputStream,
        limit: Int,
        field: String,
    ): ByteArray {
        val length = input.readInt()
        require(length in 0..limit) { "$field length $length exceeds limit $limit" }
        return ByteArray(length).also(input::readFully)
    }

    private const val LONG_TAG = 0
    private const val STRING_TAG = 1
    private const val SOCKET_ADDRESS_TAG = 2
}

internal sealed interface SupervisorValidationDecodeResult {
    data object HandshakeAccepted : SupervisorValidationDecodeResult

    data class Arguments(
        val values: List<Any>,
    ) : SupervisorValidationDecodeResult

    data class ProtocolViolation(
        val reason: String,
    ) : SupervisorValidationDecodeResult
}
