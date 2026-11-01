package demo.koog

import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.typeToken
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalRemoteCallException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptedModelTest {
    @Test fun `remote validation remains invalid input without guessing unknown errors`() {
        assertEquals(ToolFailureKind.INVALID_INPUT, toolFailureKind(PortalRemoteCallException("java.lang.IllegalArgumentException", "private diagnostic")))
        assertEquals(ToolFailureKind.CONTAINMENT_REFUSED, toolFailureKind(PortalRemoteCallException("java.nio.file.AccessDeniedException", "private path")))
        assertEquals(ToolFailureKind.OUTCOME_UNKNOWN, toolFailureKind(PortalCallException("private transport detail", PortalInvocationEvidence.UNKNOWN_COMPLETION)))
        assertEquals(ToolFailureKind.INTERNAL_FAILURE, toolFailureKind(PortalRemoteCallException("java.lang.IllegalStateException", "private diagnostic")))
    }

    private val tool = object : Tool<ReadDocumentArguments, String>(typeToken<ReadDocumentArguments>(), typeToken<String>(), "read_document", "descriptor only") {
        override suspend fun execute(args: ReadDocumentArguments): String = error("Provider self-check must not invoke a tool")
    }
    private val llm = LLModel(OpenAILLMProvider, "self-check", listOf(LLMCapability.Tools), 8192, 256)
    private val call = MessagePart.Tool.Call("one", tool.name, "{}")

    @Test fun `missing wrong or false success history cannot complete a scripted request`(): Unit =
        runBlocking {
        for (bad in listOf(
            Prompt.Empty,
            history(MessagePart.Tool.Result("wrong", tool.name, "denied", true)),
            history(MessagePart.Tool.Result("one", tool.name, "\"wrong\"", false)),
        )) {
            ScriptedModel(setOf(tool.name), listOf(ScriptStep(call, null))).use { model ->
                model.execute(Prompt.Empty, llm, listOf(tool.descriptor))
                assertFailsWith<IllegalStateException> { model.execute(bad, llm, listOf(tool.descriptor)) }
            }
        }
    }

    @Test fun `correct output completes once and another model request rejects`(): Unit =
        runBlocking {
        ScriptedModel(setOf(tool.name), listOf(ScriptStep(call, "ok"))).use { model ->
            model.execute(Prompt.Empty, llm, listOf(tool.descriptor))
            assertEquals("script-complete", model.execute(history(MessagePart.Tool.Result("one", tool.name, "\"ok\"", false)), llm, listOf(tool.descriptor)).textContent())
            assertFailsWith<IllegalStateException> { model.execute(Prompt.Empty, llm, listOf(tool.descriptor)) }
        }
        assertFailsWith<IllegalArgumentException> { ScriptedModel(setOf(tool.name), emptyList()) }
        assertFailsWith<IllegalArgumentException> { ScriptedModel(setOf(tool.name), List(3) { ScriptStep(call, "ok") }) }
        assertFailsWith<IllegalArgumentException> { ScriptedModel(setOf(tool.name), List(2) { ScriptStep(call, "ok") }) }
    }

    @Test fun `changed successful output or arguments cannot become a completion`(): Unit =
        runBlocking {
        ScriptedModel(setOf(tool.name), listOf(ScriptStep(call, "ok"))).use { model ->
            model.execute(Prompt.Empty, llm, listOf(tool.descriptor))
            assertFailsWith<IllegalStateException> { model.execute(history(MessagePart.Tool.Result("one", tool.name, "\"wrong\"", false)), llm, listOf(tool.descriptor)) }
        }
        ScriptedModel(setOf(tool.name), listOf(ScriptStep(call, "ok"))).use { model ->
            model.execute(Prompt.Empty, llm, listOf(tool.descriptor))
            val changed = Prompt.Empty.withMessages {
                listOf(
                    Message.Assistant(call.copy(args = "{\"path\":\"changed\"}"), ResponseMetaInfo.Empty),
                    Message.User(listOf(MessagePart.Tool.Result("one", tool.name, "\"ok\"", false)), RequestMetaInfo.Empty),
                )
            }
            assertFailsWith<IllegalStateException> { model.execute(changed, llm, listOf(tool.descriptor)) }
        }
    }

    private fun history(result: MessagePart.Tool.Result): Prompt =
        Prompt.Empty.withMessages {
        listOf(Message.Assistant(call, ResponseMetaInfo.Empty), Message.User(listOf(result), RequestMetaInfo.Empty))
    }

    @Test fun `model sees only correlated category while local failure identity survives`(): Unit =
        runBlocking {
        val original = java.nio.file.AccessDeniedException("/private/DUMMY_PRIVATE_CANARY")
        val failing = object : Tool<ReadDocumentArguments, String>(typeToken<ReadDocumentArguments>(), typeToken<String>(), "read_document", "failure control") {
            override suspend fun execute(args: ReadDocumentArguments): String = throw original
        }
        val observed = runScriptedAgent(listOf(failing), listOf(ScriptStep(MessagePart.Tool.Call("safe-error", failing.name, "{\"path\":\"note.md\"}"), null)))
        assertEquals(1, observed.failures.size)
        kotlin.test.assertSame(original, observed.failures.single())
    }
}
