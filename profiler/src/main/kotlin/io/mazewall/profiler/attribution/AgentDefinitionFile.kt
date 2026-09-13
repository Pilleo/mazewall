package io.mazewall.profiler.attribution

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Reader for the native agent's immutable, post-run stack dictionary. */
public object AgentDefinitionFile {
    public class Contents(
        stacks: List<StackDefinition>,
        invocations: List<InvocationDefinition>,
        public val agentLosses: Long,
        executions: List<ExecutionContext> = emptyList(),
        /** Number of virtual-thread native proxy intervals; every one pins its carrier. */
        public val virtualPinnedScopes: Long = 0,
        /** Sum of native proxy interval durations, measured by the in-process agent. */
        public val virtualPinnedNanos: Long = 0,
        /** Optional live-publication identity; null means legacy raw MZSD input. */
        public val publication: SnapshotPublication? = null,
    ) {
        public val stacks: List<StackDefinition> = java.util.List.copyOf(stacks)
        public val invocations: List<InvocationDefinition> = java.util.List.copyOf(invocations)
        public val executions: List<ExecutionContext> = java.util.List.copyOf(executions)
    }

    public fun read(path: Path): Contents = decode(Files.readAllBytes(path))

    public fun readForSession(
        path: Path,
        expectedSessionEpoch: SessionEpoch,
    ): Contents = decodeForSession(Files.readAllBytes(path), expectedSessionEpoch)

    public fun decodeForSession(
        bytes: ByteArray,
        expectedSessionEpoch: SessionEpoch,
    ): Contents {
        val contents = decode(bytes)
        val publication = requireNotNull(contents.publication) {
            "session-bound publication is required for live session admission"
        }
        require(publication.sessionEpoch == expectedSessionEpoch) {
            "snapshot belongs to session ${publication.sessionEpoch}, expected $expectedSessionEpoch"
        }
        return contents
    }

    public fun decode(bytes: ByteArray): Contents {
        val envelope = bytes.takeIf(SnapshotEnvelope::isEnvelope)?.let(SnapshotEnvelope::decode)
        val input = ByteBuffer.wrap(envelope?.payload ?: bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() >= HEADER_SIZE) { "agent definition file is shorter than its header" }
        require(ByteArray(4).also(input::get).contentEquals(MAGIC)) { "invalid agent definition magic" }
        val version = input.get().toInt() and 0xff
        require(version in setOf(VERSION_1, VERSION_2)) { "unsupported agent definition version" }
        require(input.get() == 0.toByte()) { "agent definition reserved byte is non-zero" }
        val stacks = mutableListOf<StackDefinition>()
        val stacksById = mutableMapOf<StackTraceId, StackDefinition>()
        val invocations = mutableListOf<InvocationDefinition>()
        val invocationsById = mutableMapOf<InvocationId, InvocationDefinition>()
        val executions = mutableMapOf<ExecutionId, ExecutionContext>()
        var agentLosses = 0L
        var virtualPinnedScopes = 0L
        var virtualPinnedNanos = 0L
        while (input.hasRemaining()) {
            require(input.remaining() >= RECORD_HEADER_SIZE) { "truncated agent definition record header" }
            val type = input.get().toInt() and 0xff
            val length = input.int
            require(length >= 0 && length <= input.remaining()) { "invalid agent definition record length $length" }
            val payload = input.slice().order(ByteOrder.LITTLE_ENDIAN).apply { limit(length) }
            when (type) {
                STACK_RECORD -> {
                    val stack = decodeStack(payload)
                    val previous = stacksById.putIfAbsent(stack.stackTraceId, stack)
                    when {
                        previous == null -> stacks += stack
                        previous != stack -> throw IllegalArgumentException("conflicting stack trace id ${stack.stackTraceId.value}")
                    }
                }
                INVOCATION_RECORD -> {
                    val invocation = decodeInvocation(payload, version, executions)
                    val previous = invocationsById.putIfAbsent(invocation.invocationId, invocation)
                    when {
                        previous == null -> invocations += invocation
                        previous != invocation -> throw IllegalArgumentException("conflicting invocation id ${invocation.invocationId.value}")
                    }
                }
                EXECUTION_RECORD -> {
                    require(version >= VERSION_2) { "execution record requires definition version 2" }
                    val execution = decodeExecution(payload)
                    val previous = executions.putIfAbsent(execution.executionId, execution)
                    require(previous == null || previous == execution) {
                        "conflicting execution id ${execution.executionId.value}"
                    }
                }
                STATS_RECORD -> {
                    require(payload.remaining() in setOf(16, 32)) { "invalid agent stats record" }
                    agentLosses = Math.addExact(payload.long, payload.long)
                    if (payload.hasRemaining()) {
                        virtualPinnedScopes = payload.long
                        virtualPinnedNanos = payload.long
                    }
                }
                else -> throw IllegalArgumentException("unknown agent definition record type $type")
            }
            require(!payload.hasRemaining()) { "agent definition record has trailing bytes" }
            input.position(input.position() + length)
        }
        return Contents(
            stacks = stacks,
            invocations = invocations,
            agentLosses = agentLosses,
            executions = executions.values.toList(),
            virtualPinnedScopes = virtualPinnedScopes,
            virtualPinnedNanos = virtualPinnedNanos,
            publication = envelope?.publication,
        )
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

    private fun decodeInvocation(
        payload: ByteBuffer,
        version: Int,
        executions: Map<ExecutionId, ExecutionContext>,
    ): InvocationDefinition {
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
            4 -> CaptureFailure.EXECUTION_IDENTITY_CHANGED
            else -> throw IllegalArgumentException("invalid invocation capture failure")
        }
        val execution = if (version >= VERSION_2) {
            payload.long
                .takeIf { it != 0L }
                ?.let(::ExecutionId)
                ?.let { requireNotNull(executions[it]) { "invocation references unknown execution ${it.value}" } }
        } else {
            null
        }
        return InvocationDefinition(invocationId, parent, stackId, failure, execution)
    }

    private fun decodeExecution(payload: ByteBuffer): ExecutionContext =
        ExecutionContext(
            executionId = ExecutionId(payload.long),
            kind = when (payload.get().toInt()) {
                0 -> ExecutionKind.PLATFORM
                1 -> ExecutionKind.VIRTUAL
                else -> throw IllegalArgumentException("invalid execution kind")
            },
        )

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
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2
    private const val STACK_RECORD = 1
    private const val INVOCATION_RECORD = 2
    private const val STATS_RECORD = 3
    private const val EXECUTION_RECORD = 4
    private val MAGIC = "MZSD".toByteArray(StandardCharsets.US_ASCII)
}
