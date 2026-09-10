package demo.vulnapp

import demo.vulnapp.config.MazewallProtectionBootstrap
import demo.vulnapp.config.ProfileArtifactPublisher
import demo.vulnapp.service.DefaultFileService
import demo.vulnapp.service.FileService
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VulnerableWebAppFreshJvmTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `unprotected exploit succeeds without damaging preexisting paths`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "unprotectedBody")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `protected exploit is denied while benign work succeeds`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "protectedBody")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `profiling records nonempty evidence`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "profileBody")
    }

    @Suppress("unused")
    fun unprotectedBody() {
        val root = Files.createTempDirectory("mazewall-web-unprotected-")
        val marker = root.resolve("marker")
        Files.writeString(marker, "preserve")
        val created = root.resolve("created")
        try {
            startApplication(enabled = false).use { application ->
                assertTrue(application.getBean(FileService::class.java) is DefaultFileService)
                assertEquals("false", application.environment.getProperty(MazewallProtectionBootstrap.RESOLVED_PROPERTY))
                val response = postCommand(application, "printf exploited > '${created}'")
                assertEquals(200, response.statusCode())
                assertEquals("exploited", Files.readString(created))
                assertEquals("preserve", Files.readString(marker))
            }
            assertEquals("preserve", Files.readString(marker))
        } finally {
            Files.deleteIfExists(created)
            Files.deleteIfExists(marker)
            Files.deleteIfExists(root)
        }
    }

    @Suppress("unused")
    fun protectedBody() {
        val root = Files.createTempDirectory("mazewall-web-protected-")
        val marker = root.resolve("marker")
        try {
            startApplication(enabled = true).use { application ->
                assertFalse(application.getBean(FileService::class.java) is DefaultFileService)
                assertEquals("true", application.environment.getProperty(MazewallProtectionBootstrap.RESOLVED_PROPERTY))
                val benign = getAdmin(application, "1.0")
                assertEquals(200, benign.statusCode())
                assertTrue(benign.body().contains("Log processed"))

                val exploit = postCommand(application, "printf escaped > '${marker}'")
                assertTrue(exploit.statusCode() >= 500)
                assertFalse(Files.exists(marker), "protected HTTP command must not create its marker")
            }
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(root)
        }
    }

    @Suppress("unused")
    fun profileBody() {
        val root = Files.createTempDirectory("mazewall-web-profile-")
        val target = root.resolve("sbob.json")
        val marker = root.resolve("profile-marker")
        System.setProperty("mazewall.sbob.path", target.toString())
        try {
            startApplication(enabled = false, profile = true, sbobPath = target).use { application ->
                assertFalse(application.getBean(FileService::class.java) is DefaultFileService)
                assertEquals(200, postCommand(application, "printf profiled > '${marker}'").statusCode())
                assertEquals("profiled", Files.readString(marker))
                assertFalse(Files.exists(target), "current-generation pointer must be published only during shutdown")
            }

            val generation = ProfileArtifactPublisher.readCurrent(target)
            val evidence = io.mazewall.profiler.BillOfBehavior.fromFile(generation.evidence)
            assertTrue(evidence.execs.isNotEmpty() || evidence.syscalls.isNotEmpty(), "shutdown must publish observed HTTP service evidence")
            assertTrue(Files.readString(generation.stackDictionary).isNotBlank())
            assertTrue(Files.readString(generation.integrity).contains("\"complete\":true"))
        } finally {
            System.clearProperty("mazewall.sbob.path")
            root.toFile().deleteRecursively()
        }
    }

    private fun startApplication(
        enabled: Boolean,
        profile: Boolean = false,
        sbobPath: java.nio.file.Path? = null,
    ): ConfigurableApplicationContext {
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
        val application = SpringApplication(VulnAppApplication::class.java)
        application.addListeners(MazewallProtectionBootstrap())
        val arguments = mutableListOf(
            "--mazewall.enabled=$enabled",
            "--mazewall.profile=$profile",
            "--server.port=0",
            "--spring.main.banner-mode=off",
        )
        sbobPath?.let { arguments += "--mazewall.sbob.path=$it" }
        return application.run(*arguments.toTypedArray()).also {
            assertEquals("127.0.0.1", it.environment.getProperty("server.address"))
        }
    }

    private fun postCommand(application: ConfigurableApplicationContext, command: String): HttpResponse<String> {
        val body = "cmd=" + URLEncoder.encode(command, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(uri(application, "/command"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getAdmin(application: ConfigurableApplicationContext, version: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri(application, "/admin/log"))
            .header("X-Api-Version", version)
            .GET()
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun uri(application: ConfigurableApplicationContext, path: String): URI {
        val port = (application as WebServerApplicationContext).webServer.port
        return URI("http://127.0.0.1:$port$path")
    }
}
