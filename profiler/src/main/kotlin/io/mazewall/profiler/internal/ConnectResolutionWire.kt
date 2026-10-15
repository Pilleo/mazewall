package io.mazewall.profiler.internal

import io.mazewall.profiler.api.ConnectResolution
import io.mazewall.profiler.api.NetworkEndpoint
import java.io.DataInputStream
import java.io.DataOutputStream

/** Version 2 adds bounded CONNECT evidence; version 1 remains an uninspected destination. */
internal object ConnectResolutionWire {
    const val VERSION = 2
    private const val MAX_HOST_BYTES = 512
    private const val MAX_DETAIL_BYTES = 8192
    private const val MAX_PORT = 65535
    private const val UNSUPPORTED_FAMILY = 3
    private const val MEMORY_READ_FAILED = 4

    fun write(
        output: DataOutputStream,
        name: String,
        resolution: ConnectResolution,
    ) {
        validateOperation(name, resolution)
        when (resolution) {
            ConnectResolution.NotApplicable -> output.writeByte(0)
            is ConnectResolution.Resolved -> {
                output.writeByte(1)
                require(resolution.endpoint.host.isNotEmpty())
                writeString(output, resolution.endpoint.host, MAX_HOST_BYTES)
                val port = resolution.endpoint.port
                require(port == null || port in 0..MAX_PORT)
                output.writeInt(port ?: -1)
            }
            is ConnectResolution.Failed -> {
                output.writeByte(2)
                output.writeByte(
                    when (resolution.reason) {
                    ConnectResolution.Reason.NOT_CAPTURED -> 0
                    ConnectResolution.Reason.INVALID_ARGUMENTS -> 1
                    ConnectResolution.Reason.TRUNCATED -> 2
                    ConnectResolution.Reason.UNSUPPORTED_FAMILY -> UNSUPPORTED_FAMILY
                    ConnectResolution.Reason.MEMORY_READ_FAILED -> MEMORY_READ_FAILED
                },
                )
                writeString(output, resolution.detail, MAX_DETAIL_BYTES)
            }
        }
    }

    fun read(
        input: DataInputStream,
        name: String,
    ): ConnectResolution {
        val resolution = when (input.readUnsignedByte()) {
            0 -> ConnectResolution.NotApplicable
            1 -> {
                val host = readString(input, MAX_HOST_BYTES)
                require(host.isNotEmpty() && '\u0000' !in host) { "Invalid CONNECT host" }
                val port = input.readInt()
                require(port in -1..MAX_PORT) { "Invalid CONNECT port" }
                ConnectResolution.Resolved(NetworkEndpoint(host, port.takeUnless { it == -1 }))
            }
            2 -> {
                val reason = when (input.readUnsignedByte()) {
                    0 -> ConnectResolution.Reason.NOT_CAPTURED
                    1 -> ConnectResolution.Reason.INVALID_ARGUMENTS
                    2 -> ConnectResolution.Reason.TRUNCATED
                    UNSUPPORTED_FAMILY -> ConnectResolution.Reason.UNSUPPORTED_FAMILY
                    MEMORY_READ_FAILED -> ConnectResolution.Reason.MEMORY_READ_FAILED
                    else -> throw IllegalArgumentException("Invalid CONNECT failure reason")
                }
                ConnectResolution.Failed(reason, readString(input, MAX_DETAIL_BYTES))
            }
            else -> throw IllegalArgumentException("Invalid CONNECT resolution tag")
        }
        validateOperation(name, resolution)
        return resolution
    }

    private fun validateOperation(
        name: String,
        resolution: ConnectResolution,
    ) {
        require(name.equals("CONNECT", ignoreCase = true) == (resolution != ConnectResolution.NotApplicable)) { "CONNECT evidence operation mismatch" }
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
        limit: Int,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "CONNECT evidence exceeds bound" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(
        input: DataInputStream,
        limit: Int,
    ): String {
        val size = input.readInt()
        require(size in 0..limit) { "Invalid CONNECT evidence size" }
        return decodeTraceUtf8(ByteArray(size).also(input::readFully))
    }
}
