package io.mazewall.profiler.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Reader for the native agent's immutable, post-run stack dictionary. */
public object AgentDefinitionFile {
    public data class Contents(
        val stacks: List<StackDefinition>,
        val invocations: List<InvocationDefinition>,
        val agentLosses: Long,
    )

    public fun read(path: Path): Contents = decode(Files.readAllBytes(path))

    public fun decode(bytes: ByteArray): Contents {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() >= HEADER_SIZE) { "agent definition file is shorter than its header" }
        require(ByteArray(4).also(input::get).contentEquals(MAGIC)) { "invalid agent definition magic" }
        require(input.get() == VERSION) { "unsupported agent definition version" }
        require(input.get() == 0.toByte()) { "agent definition reserved byte is non-zero" }
        val stacks = mutableListOf<StackDefinition>()
        val invocations = mutableListOf<InvocationDefinition>()
        var agentLosses = 0L
        while (input.hasRemaining()) {
            require(input.remaining() >= RECORD_HEADER_SIZE) { "truncated agent definition record header" }
            val type = input.get().toInt() and 0xff
            val length = input.int
            require(length >= 0 && length <= input.remaining()) { "invalid agent definition record length $length" }
            val payload = input.slice().order(ByteOrder.LITTLE_ENDIAN).apply { limit(length) }
            when (type) {
                STACK_RECORD -> stacks += decodeStack(payload)
                INVOCATION_RECORD -> invocations += decodeInvocation(payload)
                STATS_RECORD -> {
                    require(payload.remaining() == 16) { "invalid agent stats record" }
                    agentLosses = Math.addExact(payload.long, payload.long)
                }
                else -> throw IllegalArgumentException("unknown agent definition record type $type")
            }
            require(!payload.hasRemaining()) { "agent definition record has trailing bytes" }
            input.position(input.position() + length)
        }
        require(stacks.map { it.stackTraceId }.toSet().size == stacks.size) { "duplicate stack trace id" }
        require(invocations.map { it.invocationId }.toSet().size == invocations.size) { "duplicate invocation id" }
        return Contents(stacks, invocations, agentLosses)
    }

    private fun decodeStack(payload: ByteBuffer): StackDefinition {
        val id = StackTraceId(payload.int.toLong() and 0xffff_ffffL)
        val quality = when (payload.get().toInt()) {
            0 -> StackCaptureQuality.COMPLETE
            1 -> StackCaptureQuality.TRUNCATED
            else -> throw IllegalArgumentException("invalid stack capture quality")
        }
        val frameCount = payload.short.toInt() and 0xffff
        val frames = List(frameCount) {
            ManagedFrame(
                classLoaderIdentity = payload.string(),
                className = payload.string().asClassName(),
                methodName = payload.string(),
                descriptor = payload.string(),
                bytecodeLocation = payload.long,
                kind = when (payload.get().toInt()) {
                    0 -> ManagedFrameKind.JAVA
                    1 -> ManagedFrameKind.NATIVE_METHOD
                    else -> throw IllegalArgumentException("invalid managed frame kind")
                },
            )
        }
        return StackDefinition(id, frames, quality)
    }

    private fun decodeInvocation(payload: ByteBuffer): InvocationDefinition {
        val invocationId = InvocationId(payload.long)
        val parent = payload.long.takeIf { it != 0L }?.let(::InvocationId)
        val stackId = payload.int
            .toLong()
            .and(0xffff_ffffL)
            .takeIf { it != 0L }
            ?.let(::StackTraceId)
        val failure = when (payload.get().toInt()) {
            0 -> null
            1 -> CaptureFailure.STACK_WALK_FAILED
            2 -> CaptureFailure.STACK_TRUNCATED_REJECTED
            3 -> CaptureFailure.UNSUPPORTED_EXECUTION
            else -> throw IllegalArgumentException("invalid invocation capture failure")
        }
        return InvocationDefinition(invocationId, parent, stackId, failure)
    }

    private fun ByteBuffer.string(): String {
        val length = short.toInt() and 0xffff
        require(length <= remaining()) { "truncated agent definition string" }
        return StandardCharsets.UTF_8
            .decode(slice().apply { limit(length) })
            .toString()
            .also { position(position() + length) }
    }

    private fun String.asClassName(): String = if (startsWith('L') && endsWith(';')) substring(1, length - 1).replace('/', '.') else replace('/', '.')

    private const val HEADER_SIZE = 6
    private const val RECORD_HEADER_SIZE = 5
    private const val VERSION: Byte = 1
    private const val STACK_RECORD = 1
    private const val INVOCATION_RECORD = 2
    private const val STATS_RECORD = 3
    private val MAGIC = "MZSD".toByteArray(StandardCharsets.US_ASCII)
}
