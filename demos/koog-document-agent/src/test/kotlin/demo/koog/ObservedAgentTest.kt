package demo.koog

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObservedAgentTest {
    private val reader = object : Tool<ReadDocumentArguments, String>(typeToken<ReadDocumentArguments>(), typeToken<String>(), "read_document", "Ordinary host test only") {
        override suspend fun execute(args: ReadDocumentArguments): String = "actual input for ${args.path}"
    }

    @Test fun `actual tool results reach the real graph and its terminal answer is returned`() =
        runBlocking {
        val provider = IncidentFixtureExecutor(mapOf("application.log" to "actual input for application.log"), "provider's actual terminal answer")
        val observed = runObservedAgent(listOf(reader), provider, incidentFixtureModel, "test", 7)
        assertEquals("provider's actual terminal answer", observed.output)
        assertEquals(1, observed.completions)
        assertTrue(observed.failures.isEmpty())
        assertEquals(2, provider.requests.get())
        assertEquals(1, provider.closes.get())
    }

    @Test fun `provider failure stays failed and the provider is closed`(): Unit =
        runBlocking {
        val original = IOException("offline provider failure")
        val provider = IncidentFixtureExecutor(emptyMap(), "must not become an answer", original)
        val failure = assertFailsWith<Exception> { runObservedAgent(listOf(reader), provider, incidentFixtureModel, "test", 7) }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
        assertEquals(1, provider.closes.get())
    }

    @Test fun `the existing agent deadline cancels a stalled provider and closes it`(): Unit =
        runBlocking {
        val provider = IncidentFixtureExecutor(emptyMap(), "must not become an answer", holdProvider = true)
        assertFailsWith<TimeoutCancellationException> { runObservedAgent(listOf(reader), provider, incidentFixtureModel, "test", 7) }
        assertEquals(1, provider.closes.get())
    }
}
