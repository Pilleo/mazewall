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
import kotlinx.serialization.json.Json

internal data class ScriptedToolStep(val call: MessagePart.Tool.Call, val expectedOutput: String?)

internal suspend fun verifyAgentSequence(tools: List<Tool<*, *>>, first: ScriptedToolStep, second: ScriptedToolStep): List<Throwable?> {
    require(first.call.id != second.call.id && first.call.id != null && second.call.id != null)
    val steps = listOf(first, second)
    val names = tools.map { it.name }.toSet()
    require(steps.all { it.call.tool in names })
    val executor = ScriptedPromptExecutor(names, listOf(
        Message.Assistant(first.call, ResponseMetaInfo.Empty),
        Message.Assistant(second.call, ResponseMetaInfo.Empty),
        Message.Assistant("sequence-observed", ResponseMetaInfo.Empty),
    )) { turn, prompt -> verifySequenceHistory(turn, prompt, steps) }
    AgentAttempt(tools, executor).use { attempt ->
        check(attempt.agent.run("execute two explicit tool calls") == "sequence-observed")
        check(executor.calls.get() == 3)
        check(attempt.completions.get() == steps.count { it.expectedOutput != null })
        check(attempt.failures.size == steps.count { it.expectedOutput == null })
        return attempt.failures.toList()
    }
}

/** Pins the history gate itself, independently of the native tools that use it. */
internal suspend fun verifySequenceHistoryRejections(name: String) {
    val first = ScriptedToolStep(MessagePart.Tool.Call("denied-1", name, "{}"), null)
    val second = ScriptedToolStep(MessagePart.Tool.Call("allowed-2", name, "{}"), "two")
    val steps = listOf(first, second)
    val denied = MessagePart.Tool.Result("denied-1", name, "controlled denial", true)
    val allowed = MessagePart.Tool.Result("allowed-2", name, "\"two\"", false)
    verifySequenceHistory(1, Prompt.Empty, steps)
    verifySequenceHistory(2, sequencePrompt(first.call to denied), steps)
    verifySequenceHistory(3, sequencePrompt(first.call to denied, second.call to allowed), steps)
    for ((turn, bad) in listOf(
        2 to Prompt.Empty,
        2 to sequencePrompt(first.call to denied.copy(id = "wrong")),
        2 to sequencePrompt(first.call to denied.copy(isError = false)),
        3 to sequencePrompt(second.call to allowed),
        3 to sequencePrompt(second.call to allowed, first.call to denied),
        3 to sequencePrompt(first.call to denied, first.call to denied),
        3 to sequencePrompt(first.call to denied, second.call to allowed.copy(parts = listOf(MessagePart.Text("\"wrong\"")))),
    )) {
        expectFailure<IllegalStateException> { verifySequenceHistory(turn, bad, steps) }
    }
}

private fun verifySequenceHistory(turn: Int, prompt: Prompt, steps: List<ScriptedToolStep>) {
    check(turn in 1..3 && steps.size == 2)
    val previous = steps.take(turn - 1)
    val parts = prompt.messages.flatMap { it.parts }.filter { it is MessagePart.Tool.Call || it is MessagePart.Tool.Result }
    check(parts.size == previous.size * 2) { "Missing or extra tool history" }
    for ((index, step) in previous.withIndex()) {
        val call = parts[index * 2] as? MessagePart.Tool.Call ?: error("Tool call/result history reordered")
        val result = parts[index * 2 + 1] as? MessagePart.Tool.Result ?: error("Tool result/call history reordered")
        check(call.id == step.call.id && call.tool == step.call.tool && call.argsJson == step.call.argsJson)
        check(result.id == call.id && result.tool == call.tool)
        check(result.isError == (step.expectedOutput == null)) { "Failure became a successful observation" }
        if (step.expectedOutput != null) check(Json.decodeFromString<String>(result.output) == step.expectedOutput)
    }
}

private fun sequencePrompt(vararg exchanges: Pair<MessagePart.Tool.Call, MessagePart.Tool.Result>): Prompt =
    Prompt.Empty.withMessages {
        exchanges.flatMap { (call, result) -> listOf(Message.Assistant(call, ResponseMetaInfo.Empty), Message.User(listOf(result), RequestMetaInfo.Empty)) }
    }

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
