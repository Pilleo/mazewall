package io.mazewall.consumer.koog

import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo

/** Provider-only self-check: no tool is invoked and no native ownership is manufactured. */
internal suspend fun verifySequenceExecutorRejections(tools: List<Tool<*, *>>) {
    val name = tools.first().name
    val names = tools.map { it.name }.toSet()
    val descriptors = tools.map { it.descriptor }
    val model = LLModel(OpenAILLMProvider, "sequence-self-check", listOf(LLMCapability.Tools), 4096, 256)
    val responses = listOf(
        Message.Assistant(MessagePart.Tool.Call("first", name, "{}"), ResponseMetaInfo.Empty),
        Message.Assistant(MessagePart.Tool.Call("second", name, "{}"), ResponseMetaInfo.Empty),
        Message.Assistant("sequence-observed", ResponseMetaInfo.Empty),
    )
    val first = MessagePart.Tool.Result("first", name, "\"one\"", false)
    val second = MessagePart.Tool.Result("second", name, "\"two\"", false)
    val prompts = listOf(Prompt.Empty, sequenceHistory(first), sequenceHistory(first, second))
    val mutableNames = names.toMutableSet()
    val mutableResponses = responses.toMutableList()
    ScriptedPromptExecutor(mutableNames, mutableResponses) { turn, prompt -> check(prompt == prompts[turn - 1]) }.use { executor ->
        mutableNames.clear()
        mutableResponses.clear()
        for (index in prompts.indices) check(executor.execute(prompts[index], model, descriptors) == responses[index])
        expectFailure<IllegalStateException> { executor.execute(Prompt.Empty, model, descriptors) }
    }
    ScriptedPromptExecutor(names, responses) { turn, prompt -> check(prompt == prompts[turn - 1]) }.use { executor ->
        executor.execute(Prompt.Empty, model, descriptors)
        expectFailure<IllegalStateException> { executor.execute(Prompt.Empty, model, descriptors) }
    }
    for (size in listOf(1, 4)) {
        expectFailure<IllegalArgumentException> {
            ScriptedPromptExecutor(names, List(size) { Message.Assistant("done", ResponseMetaInfo.Empty) }) { _, _ -> }
        }
    }
}

private fun sequenceHistory(vararg results: MessagePart.Tool.Result): Prompt =
    Prompt.Empty.withMessages { listOf(Message.User(results.toList(), RequestMetaInfo.Empty)) }
