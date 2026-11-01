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
    registeredNames: Set<String>,
    responses: List<Message.Assistant>,
    private val verifyHistory: (Int, Prompt) -> Unit,
) : PromptExecutor() {
    private val names = registeredNames.toSet()
    private val script = responses.toList()
    val maximumRequests: Int = script.size
    val calls = AtomicInteger()
    private var closed = false

    init {
        require(names.size in 1..2 && names.none { it.isBlank() })
        require(script.size in 2..3) { "Only one-call or two-call scripts are supported" }
    }

    constructor(registeredName: String, requestedName: String, arguments: String, expectedOutput: String?) : this(
        setOf(registeredName),
        listOf(
            Message.Assistant(MessagePart.Tool.Call("contract-call", requestedName, arguments), ResponseMetaInfo.Empty),
            Message.Assistant(if (expectedOutput == null) "observed-tool-failure" else "observed-tool-success", ResponseMetaInfo.Empty),
        ),
        { turn, prompt -> verifySingleHistory(turn, prompt, registeredName, requestedName, expectedOutput) },
    )

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        check(!closed)
        check(tools.size == names.size && tools.map { it.name }.toSet() == names)
        val turn = calls.incrementAndGet()
        check(turn <= maximumRequests) { "Unexpected extra model request; scripted qualification never retries tools" }
        verifyHistory(turn, prompt)
        return script[turn - 1]
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("Streaming is outside this contract")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Moderation is outside this contract")
    override fun close() { closed = true }
}

private fun verifySingleHistory(turn: Int, prompt: Prompt, registeredName: String, requestedName: String, expectedOutput: String?) {
    val results = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
    when (turn) {
        1 -> check(results.isEmpty())
        2 -> {
            if (requestedName != registeredName) {
                // Koog's default missing-tool conversion sends this observation as text, not a tool part.
                check(results.isEmpty() && expectedOutput == null)
                val text = prompt.messages.last().parts.filterIsInstance<MessagePart.Text>().single().text
                val missing = Json.parseToJsonElement(text).jsonObject
                check(missing["tool_call_id"]?.jsonPrimitive?.content == "contract-call")
                check(missing["tool_name"]?.jsonPrimitive?.content == requestedName)
                check(missing["tool_result"]?.jsonPrimitive?.content?.contains("not found") == true)
            } else {
                check(results.size == 1) { "Single-call history must contain exactly one tool result" }
                val result = results.single()
                check(result.id == "contract-call" && result.tool == requestedName)
                check(result.isError == (expectedOutput == null)) { "Tool failure must not become a successful result: $result" }
                if (expectedOutput != null) check(Json.decodeFromString<String>(result.output) == expectedOutput)
            }
        }
        else -> error("Unexpected single-call model turn")
    }
}

/** Observe upstream failure events without changing tool outcomes or installing a production feature. */
internal class AgentAttempt(tools: List<Tool<*, *>>, val executor: ScriptedPromptExecutor) : AutoCloseable {
    private val registeredTools = tools.toList()
    init { require(registeredTools.size in 1..2 && registeredTools.map { it.name }.distinct().size == registeredTools.size) }
    constructor(tool: Tool<*, *>, arguments: String, expectedOutput: String?, requestedName: String = tool.name) :
        this(listOf(tool), ScriptedPromptExecutor(tool.name, requestedName, arguments, expectedOutput))
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
        toolRegistry = ToolRegistry { registeredTools.forEach { tool(it) } },
        maxIterations = 2 * executor.maximumRequests + 1, // Two/three model requests require five/seven graph nodes.
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
