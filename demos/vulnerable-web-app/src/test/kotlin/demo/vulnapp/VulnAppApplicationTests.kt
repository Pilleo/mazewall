package demo.vulnapp

import demo.vulnapp.config.MazewallProtectionBootstrap
import demo.vulnapp.config.ServiceResourceBindings
import demo.vulnapp.service.FileService
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VulnAppApplicationTests {
    @Test
    fun `profiling contexts isolate their system property and owned roots`() {
        val originalProfilePath = System.getProperty(PROFILE_PATH_PROPERTY)
        val firstRoot = withProfiledApplication { application, _ ->
            assertNotNull(application.getBean(FileService::class.java))
        }
        assertFalse(Files.exists(firstRoot))
        assertEquals(originalProfilePath, System.getProperty(PROFILE_PATH_PROPERTY))

        lateinit var secondRoot: Path
        val failure = runCatching {
            withProfiledApplication { application, root ->
                secondRoot = root
                val result = application.getBean(FileService::class.java).runCommand("echo Hello Profiler")
                assertTrue(result.contains("Executed"))
                error("deliberate failure after profiled work")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(Files.exists(secondRoot))
        assertEquals(originalProfilePath, System.getProperty(PROFILE_PATH_PROPERTY))
    }

    private fun withProfiledApplication(block: (ConfigurableApplicationContext, Path) -> Unit): Path {
        val previousProfilePath = System.getProperty(PROFILE_PATH_PROPERTY)
        val previousLoggingSystem = System.getProperty(LOGGING_SYSTEM_PROPERTY)
        val root = Files.createTempDirectory("mazewall-web-context-")
        val resources = ServiceResourceBindings(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("uploads")))
        val output = root.resolve("sbob.json")
        System.setProperty(PROFILE_PATH_PROPERTY, output.toString())
        System.setProperty(LOGGING_SYSTEM_PROPERTY, "none")

        try {
            val application = SpringApplication(VulnAppApplication::class.java)
            application.addListeners(MazewallProtectionBootstrap())
            application.addInitializers(
                org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                    context.beanFactory.registerSingleton("serviceResourceBindings", resources)
                },
            )
            application
                .run(
                "--mazewall.profile=true",
                "--mazewall.enabled=false",
                "--mazewall.sbob.path=$output",
                "--server.port=0",
                "--spring.main.banner-mode=off",
                ).use { block(it, root) }
        } finally {
            restoreProperty(PROFILE_PATH_PROPERTY, previousProfilePath)
            restoreProperty(LOGGING_SYSTEM_PROPERTY, previousLoggingSystem)
            root.toFile().deleteRecursively()
        }
        return root
    }

    private fun restoreProperty(
        name: String,
        previous: String?,
    ) {
        if (previous == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, previous)
        }
    }

    private companion object {
        const val PROFILE_PATH_PROPERTY = "mazewall.sbob.path"
        const val LOGGING_SYSTEM_PROPERTY = "org.springframework.boot.logging.LoggingSystem"
    }
}
