package io.mazewall.consumer.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Consumer-only model double; the registry, JSON, agent loop and protected tools are real. */
internal class ScriptedPromptExecutor(
    private val registeredName: String,
    private val requestedName: String,
    private val arguments: String,
    private val expectedOutput: String?,
) : PromptExecutor() {
    val calls = AtomicInteger()
    private var closed = false

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        check(!closed)
        check(tools.map { it.name } == listOf(registeredName))
        val results = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        return when (calls.incrementAndGet()) {
            1 -> {
                check(results.isEmpty())
                Message.Assistant(MessagePart.Tool.Call("contract-call", requestedName, arguments), ResponseMetaInfo.Empty)
            }
            2 -> {
                if (requestedName != registeredName) {
                    // Koog's default missing-tool conversion sends this observation as text, not a tool part.
                    check(results.isEmpty() && expectedOutput == null)
                    val text = prompt.messages.last().parts.filterIsInstance<MessagePart.Text>().single().text
                    val missing = Json.parseToJsonElement(text).jsonObject
                    check(missing["tool_call_id"]?.jsonPrimitive?.content == "contract-call")
                    check(missing["tool_name"]?.jsonPrimitive?.content == requestedName)
                    check(missing["tool_result"]?.jsonPrimitive?.content?.contains("not found") == true)
                    return Message.Assistant("observed-tool-failure", ResponseMetaInfo.Empty)
                }
                check(results.size == 1) { "Missing tool result for $requestedName/$arguments: ${prompt.messages}" }
                val result = results.single()
                check(result.id == "contract-call" && result.tool == requestedName)
                check(result.isError == (expectedOutput == null)) { "Tool failure must not become a successful result: $result" }
                if (expectedOutput != null) check(Json.decodeFromString<String>(result.output) == expectedOutput) { "Unexpected actual tool output: ${result.output}" }
                Message.Assistant(if (result.isError) "observed-tool-failure" else "observed-tool-success", ResponseMetaInfo.Empty)
            }
            else -> error("Unexpected extra model request; scripted qualification never retries tools")
        }
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("Streaming is outside this contract")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Moderation is outside this contract")
    override fun close() { closed = true }
}

/** Observe upstream failure events without changing tool outcomes or installing a production feature. */
internal class AgentAttempt(tool: Tool<*, *>, arguments: String, expectedOutput: String?, requestedName: String = tool.name) : AutoCloseable {
    val executor = ScriptedPromptExecutor(tool.name, requestedName, arguments, expectedOutput)
    val failures = CopyOnWriteArrayList<Throwable?>()
    val completions = AtomicInteger()
    private val events = object : AIAgentGraphFeature<FeatureConfig, Unit> {
        override val key = createStorageKey<Unit>("consumer-tool-events")
        override fun createInitialConfig(agentConfig: AIAgentConfig): FeatureConfig = object : FeatureConfig() {}
        override fun install(config: FeatureConfig, pipeline: AIAgentGraphPipeline) {
            pipeline.interceptToolCallFailed(this) { failures.add(it.error) }
            pipeline.interceptToolValidationFailed(this) { failures.add(it.error) }
            pipeline.interceptToolCallCompleted(this) { completions.incrementAndGet() }
        }
    }
    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = LLModel(OpenAILLMProvider, "scripted-contract", listOf(LLMCapability.Tools), 4096, 256),
        toolRegistry = ToolRegistry { tool(tool) },
        maxIterations = 5, // start, model, tool, model-with-result, finish; executor still permits only two requests.
        installFeatures = { install(events) },
    )

    suspend fun verifySuccess() {
        check(agent.run("execute the fixed script") == "observed-tool-success")
        check(executor.calls.get() == 2 && completions.get() == 1 && failures.isEmpty())
    }

    suspend fun verifyFailure(): Throwable? {
        check(agent.run("execute the fixed script") == "observed-tool-failure")
        check(executor.calls.get() == 2 && completions.get() == 0 && failures.size == 1)
        return failures.single()
    }

    override fun close() { try { runBlocking { agent.close() } } finally { executor.close() } }
}

/** The model double must reject omitted history and extra requests; this never invokes the tool. */
internal suspend fun verifyExecutorRejections(tool: Tool<*, *>) {
    val model = LLModel(OpenAILLMProvider, "script-self-check", listOf(LLMCapability.Tools), 4096, 256)
    ScriptedPromptExecutor(tool.name, tool.name, "{}", null).use { executor ->
        executor.execute(Prompt.Empty, model, listOf(tool.descriptor))
        expectFailure<IllegalStateException> { executor.execute(Prompt.Empty, model, listOf(tool.descriptor)) }
        expectFailure<IllegalStateException> { executor.execute(Prompt.Empty, model, listOf(tool.descriptor)) }
    }
}
