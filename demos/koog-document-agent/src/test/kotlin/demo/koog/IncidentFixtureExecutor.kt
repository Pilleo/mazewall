package demo.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/** Explicit test double. It checks real returned tool data before emitting a fixture answer. */
internal class IncidentFixtureExecutor(
    documents: Map<String, String>,
    private val answer: String,
    private val providerFailure: Throwable? = null,
    private val holdProvider: Boolean = false,
) : PromptExecutor() {
    private val documents = documents.toList()
    val requests = AtomicInteger()
    val closes = AtomicInteger()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        check(closes.get() == 0 && tools.map { it.name } == listOf("read_document"))
        providerFailure?.let { throw it }
        if (holdProvider) awaitCancellation()
        val turn = requests.incrementAndGet()
        check(turn in 1..documents.size + 1)
        val history = prompt.messages.flatMap { it.parts }.filter { it is MessagePart.Tool.Call || it is MessagePart.Tool.Result }
        check(history.size == (turn - 1) * 2)
        documents.take(turn - 1).forEachIndexed { index, (path, expected) ->
            val call = history[index * 2] as MessagePart.Tool.Call
            val result = history[index * 2 + 1] as MessagePart.Tool.Result
            check(call.id == "incident-$index" && result.id == call.id && result.tool == "read_document" && !result.isError)
            check(Json.decodeFromString<ReadDocumentArguments>(call.args) == ReadDocumentArguments(path))
            check(Json.decodeFromString<String>(result.output) == expected) { "Fixture answer cannot replace actual successful reads" }
        }
        return if (turn <= documents.size) {
            Message.Assistant(
                MessagePart.Tool.Call(
                    "incident-${turn - 1}",
                    "read_document",
            Json.encodeToString(ReadDocumentArguments(documents[turn - 1].first)),
                ),
                ResponseMetaInfo.Empty,
            )
        } else {
            Message.Assistant(answer, ResponseMetaInfo.Empty)
        }
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): kotlinx.coroutines.flow.Flow<StreamFrame> = error("Fixture does not stream")

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = error("Fixture does not moderate")

    override fun close() {
        closes.incrementAndGet()
    }
}

internal val incidentFixtureModel = LLModel(OpenAILLMProvider, "explicit-offline-fixture", listOf(LLMCapability.Tools), 16_384, 1024)
