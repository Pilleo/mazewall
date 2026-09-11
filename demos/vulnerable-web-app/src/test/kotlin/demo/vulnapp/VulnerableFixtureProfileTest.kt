package demo.vulnapp

import demo.vulnapp.config.MazewallDisabledConfig
import demo.vulnapp.controller.ImportController
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VulnerableFixtureProfileTest {
    @Test
    fun `fixture routes stay disabled even when fixture classes are available`() {
        context(fixtureEnabled = false).use { context ->
            assertTrue(context.getBeansOfType(ImportController::class.java).isEmpty())
        }
    }

    @Test
    fun `explicit isolated fixture profile activates attack routes`() {
        context(fixtureEnabled = true).use { context ->
            assertEquals(1, context.getBeansOfType(ImportController::class.java).size)
        }
    }

    private fun context(fixtureEnabled: Boolean): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            if (fixtureEnabled) environment.setActiveProfiles("isolated-vulnerable-fixture")
            register(MazewallDisabledConfig::class.java, ImportController::class.java)
            refresh()
        }
}
