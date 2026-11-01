package demo.koog

import ai.koog.agents.core.tools.Tool
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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

internal data class ScriptStep(
    val call: MessagePart.Tool.Call,
    val expectedOutput: String?,
)

/** Offline model double only. Registry, agent graph, codecs, tools and kernel restrictions are real. */
internal class ScriptedModel(
    names: Set<String>,
    steps: List<ScriptStep>,
) : PromptExecutor() {
    private val names = names.toSet()
    private val steps = steps.toList()
    val requests = AtomicInteger()
    private var closed = false

    init {
        require(
            this.steps.size in 1..2 &&
            this.steps
            .map { it.call.id }
            .distinct()
            .size == this.steps.size,
        )
        require(this.steps.all { !it.call.id.isNullOrBlank() && it.call.tool in this.names })
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        check(!closed && tools.size == names.size && tools.map { it.name }.toSet() == names)
        val turn = requests.incrementAndGet()
        check(turn <= steps.size + 1) { "Unexpected model request; this example never retries tools" }
        val history = prompt.messages.flatMap { it.parts }.filter { it is MessagePart.Tool.Call || it is MessagePart.Tool.Result }
        check(history.size == (turn - 1) * 2)
        for ((index, step) in steps.take(turn - 1).withIndex()) {
            val call = history[index * 2] as? MessagePart.Tool.Call ?: error("Reordered tool call")
            val result = history[index * 2 + 1] as? MessagePart.Tool.Result ?: error("Missing tool result")
            check(call.id == step.call.id && call.tool == step.call.tool && call.argsJson == step.call.argsJson)
            check(result.id == call.id && result.tool == call.tool && result.isError == (step.expectedOutput == null))
            if (step.expectedOutput != null) {
                check(Json.decodeFromString<String>(result.output) == step.expectedOutput)
            } else {
                check(Regex("(INVALID_INPUT|AUTHORIZATION_DENIED|CONTAINMENT_REFUSED|BUSY|CANCELLED|OUTCOME_UNKNOWN|INTERNAL_FAILURE) request=[0-9a-f-]{36}").matches(result.output)) {
                "Model received an unclassified or unsafe tool failure"
            }
            }
        }
        return if (turn <= steps.size) {
            Message.Assistant(steps[turn - 1].call, ResponseMetaInfo.Empty)
        } else {
            Message.Assistant("script-complete", ResponseMetaInfo.Empty)
        }
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = error("No streaming model is configured")

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = error("No moderation model is configured")

    override fun close() {
        closed = true
    }
}

internal data class AgentObservation(
    val completions: Int,
    val failures: List<Throwable?>,
    val modelRequests: Int,
)

internal suspend fun runScriptedAgent(
    tools: List<Tool<*, *>>,
    steps: List<ScriptStep>,
): AgentObservation {
    val model = ScriptedModel(tools.map { it.name }.toSet(), steps)
    val result = runObservedAgent(
        tools,
        model,
        LLModel(OpenAILLMProvider, "offline-script", listOf(LLMCapability.Tools), 8192, 256),
        "Execute the labelled offline document workflow",
        2 * (steps.size + 1) + 1,
    )
    check(result.output == "script-complete" && model.requests.get() == steps.size + 1)
    check(result.completions == steps.count { it.expectedOutput != null } && result.failures.size == steps.count { it.expectedOutput == null })
    return AgentObservation(result.completions, result.failures, model.requests.get())
}
