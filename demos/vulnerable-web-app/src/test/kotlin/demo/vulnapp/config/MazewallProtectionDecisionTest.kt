package demo.vulnapp.config

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MazewallProtectionDecisionTest {
    @Test
    fun `spring environment is the single source of protection truth`() {
        assertTrue(MazewallProtectionDecision.resolve(MockEnvironment().withProperty("mazewall.enabled", "true")).enabled)
        assertFalse(MazewallProtectionDecision.resolve(MockEnvironment().withProperty("mazewall.enabled", "false")).enabled)
    }

    @Test
    fun `missing setting is fail-safe disabled for the deliberately vulnerable instance`() {
        assertFalse(MazewallProtectionDecision.resolve(MockEnvironment()).enabled)
    }

    @Test
    fun `profiling and enforcement cannot be enabled together`() {
        val environment = MockEnvironment()
            .withProperty("mazewall.enabled", "true")
            .withProperty("mazewall.profile", "true")

        val failure = assertFailsWith<IllegalArgumentException> {
            MazewallProtectionDecision.resolve(environment)
        }

        assertTrue(failure.message.orEmpty().contains("cannot both be enabled"))
    }

    @Test
    fun `bootstrap publishes the resolved decision and installs exactly once`() {
        val installs = AtomicInteger()
        val environment = MockEnvironment().withProperty("mazewall.enabled", "true")
        val bootstrap = MazewallProtectionBootstrap { installs.incrementAndGet() }

        assertTrue(bootstrap.prepare(environment).enabled)
        assertEquals("true", environment.getProperty(MazewallProtectionBootstrap.RESOLVED_PROPERTY))
        assertEquals(1, installs.get())
        kotlin.test.assertFailsWith<IllegalStateException> { bootstrap.prepare(environment) }
    }
}
