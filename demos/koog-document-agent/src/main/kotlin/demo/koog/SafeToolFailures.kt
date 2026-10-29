package demo.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalRemoteCallException
import io.mazewall.portal.PortalStateLostException
import kotlinx.coroutines.flow.Flow
import java.nio.file.AccessDeniedException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class ToolFailureKind { INVALID_INPUT, AUTHORIZATION_DENIED, CONTAINMENT_REFUSED, BUSY, CANCELLED, OUTCOME_UNKNOWN, INTERNAL_FAILURE }

internal fun toolFailureKind(error: Throwable?): ToolFailureKind {
    var cause = error
    repeat(16) {
        val current = cause
        when (current) {
            is PortalStateLostException -> return ToolFailureKind.OUTCOME_UNKNOWN
            is PortalRemoteCallException -> if (current.remoteType == "java.nio.file.AccessDeniedException") return ToolFailureKind.CONTAINMENT_REFUSED
            is ContainmentViolationException, is AccessDeniedException -> return ToolFailureKind.CONTAINMENT_REFUSED
            is java.util.concurrent.CancellationException -> return ToolFailureKind.CANCELLED
            is java.util.concurrent.TimeoutException, is InterruptedException -> return ToolFailureKind.OUTCOME_UNKNOWN
            is java.util.concurrent.RejectedExecutionException -> return ToolFailureKind.BUSY
            is IllegalArgumentException -> return ToolFailureKind.INVALID_INPUT
        }
        if (current is PortalCallException && current.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION) return ToolFailureKind.OUTCOME_UNKNOWN
        cause = cause?.cause
    }
    return ToolFailureKind.INTERNAL_FAILURE
}

/** Replaces error observations before the executor boundary, not successful content or model-supplied arguments. */
internal class SafeToolFailures(
    private val provider: PromptExecutor,
) : PromptExecutor() {
    private val observations = ConcurrentHashMap<String, String>()

    fun record(
        callId: String?,
        original: Throwable?,
    ) {
        require(!callId.isNullOrBlank())
        observations.putIfAbsent(callId, "${toolFailureKind(original)} request=${UUID.randomUUID()}")
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val safe = prompt.withMessages {
            prompt.messages.map { message ->
                if (message is Message.User) {
                    message.copy(
                        parts = message.parts.map { part ->
                    if (part is MessagePart.Tool.Result && part.isError) {
                        val output = checkNotNull(observations[part.id]) { "No local failure correlation; refusing provider request" }
                        MessagePart.Tool.Result(part.id, part.tool, output, true)
                    } else {
                        part
                    }
                },
                    )
                } else {
                    check(message.parts.none { it is MessagePart.Tool.Result && it.isError }) { "Unsupported failure message; refusing provider request" }
                    message
                }
            }
        }
        return provider.execute(safe, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = error("Streaming is not configured")

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = error("Moderation is not configured")

    override fun close() = provider.close()
}
