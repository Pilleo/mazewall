package demo.koog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IncidentInvocationTest {
    @Test fun `provider and model must be explicitly selected with no local or scripted default`() {
        assertEquals(IncidentInvocation(Path.of("bundle"), IncidentProvider.OPENAI, "operator-model"), parseIncidentInvocation(arrayOf("bundle", "openai", "operator-model")))
        assertEquals(IncidentProvider.OLLAMA, parseIncidentInvocation(arrayOf("bundle", "ollama", "prepared-model:small")).provider)
        for (bad in listOf(emptyArray(), arrayOf("bundle", "model"), arrayOf("bundle", "fixture", "model"), arrayOf("bundle", "auto", "model"))) {
            assertFailsWith<IllegalArgumentException> { parseIncidentInvocation(bad) }
        }
    }

    @Test fun `model identifiers cannot inject terminal control or excessive configuration`() {
        for (bad in listOf("", " ", "bad\nmodel", "\u001b[2J", "x".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { parseIncidentInvocation(arrayOf("bundle", "openai", bad)) }
        }
    }

    @Test fun `missing hosted credentials fail without a provider fallback`() {
        assertEquals("explicit-test-key", requireIncidentOpenAIKey("explicit-test-key"))
        for (bad in listOf(null, "", " ")) assertFailsWith<IllegalArgumentException> { requireIncidentOpenAIKey(bad) }
    }
}
