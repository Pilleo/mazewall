package demo.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal data class ObservedAgentResult(
    val output: String,
    val completions: Int,
    val failures: List<Throwable?>,
)

internal suspend fun runObservedAgent(
    tools: List<Tool<*, *>>,
    executor: PromptExecutor,
    model: LLModel,
    input: String,
    maxIterations: Int,
): ObservedAgentResult {
    return SafeToolFailures(executor).use { safeProvider ->
        require(maxIterations in 1..24 && input.isNotBlank() && input.toByteArray(Charsets.UTF_8).size <= 4096)
        val failures = CopyOnWriteArrayList<Throwable?>()
        val completed = AtomicInteger()
        val events = object : AIAgentGraphFeature<FeatureConfig, Unit> {
            override val key = createStorageKey<Unit>("document-agent-observations")

            override fun createInitialConfig(agentConfig: AIAgentConfig): FeatureConfig = object : FeatureConfig() {}

            override fun install(
                config: FeatureConfig,
                pipeline: AIAgentGraphPipeline,
            ) {
                pipeline.interceptToolCallFailed(this) {
                    failures.add(it.error)
                    safeProvider.record(it.toolCallId, it.error)
                }
                pipeline.interceptToolValidationFailed(this) {
                    failures.add(it.error)
                    safeProvider.record(it.toolCallId, it.error)
                }
                pipeline.interceptToolCallCompleted(this) { completed.incrementAndGet() }
            }
        }
        val agent = AIAgent(
            promptExecutor = safeProvider,
            llmModel = model,
            toolRegistry = ToolRegistry { tools.forEach { tool(it) } },
            maxIterations = maxIterations,
            installFeatures = { install(events) },
        )
        AutoCloseable { runBlocking { agent.close() } }.use {
            val answer = withTimeout(10_000) { agent.run(input) }
            ObservedAgentResult(answer, completed.get(), failures.toList())
        }
    }
}
