package demo.vulnapp

import demo.vulnapp.config.ExecutorDrain
import demo.vulnapp.config.MazewallProtectionBootstrap
import demo.vulnapp.config.ProfileArtifactPublisher
import demo.vulnapp.config.collectorCoverage
import demo.vulnapp.service.DefaultFileService
import demo.vulnapp.service.FileService
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
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
                assertEquals(404, missingCommandRoute(application).statusCode())
                for (path in listOf("/import/java", "/import/jackson", "/import/yaml", "/import/xstream")) {
                    val request = HttpRequest
                        .newBuilder(uri(application, path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build()
                    assertEquals(404, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                }
                val payload = HttpRequest.newBuilder(uri(application, "/exploit/payload?command=true")).GET().build()
                assertEquals(404, HttpClient.newHttpClient().send(payload, HttpResponse.BodyHandlers.ofString()).statusCode())
                application.getBean(FileService::class.java).runCommand("printf exploited > '$created'")
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
        val target = root.resolve("sbob.json")
        ProfileArtifactPublisher.publish(target, io.mazewall.profiler.BillOfBehavior(), ExecutorDrain.Complete, listOf(collectorCoverage()))
        System.setProperty("mazewall.sbob.path", target.toString())
        try {
            startApplication(enabled = true).use { application ->
                assertFalse(application.getBean(FileService::class.java) is DefaultFileService)
                assertEquals("true", application.environment.getProperty(MazewallProtectionBootstrap.RESOLVED_PROPERTY))
                val benign = getAdmin(application, "1.0")
                assertEquals(200, benign.statusCode())
                assertTrue(benign.body().contains("Log processed"))

                val failure = runCatching {
                    application.getBean(FileService::class.java).runCommand("printf escaped > '$marker'")
                }.exceptionOrNull()
                assertTrue(ContainmentViolationDetector.findViolationCause(requireNotNull(failure)) != null)
                assertFalse(Files.exists(marker), "protected service command must not create its marker")
            }
        } finally {
            System.clearProperty("mazewall.sbob.path")
            root.toFile().deleteRecursively()
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
                assertEquals(200, getAdmin(application, "profile-control").statusCode())
                application.getBean(FileService::class.java).runCommand("printf profiled > '$marker'")
                assertEquals("profiled", Files.readString(marker))
                assertFalse(Files.exists(target), "current-generation pointer must be published only during shutdown")
            }

            val generation = ProfileArtifactPublisher.readCurrent(target)
            val evidence = io.mazewall.profiler.BillOfBehavior
                .fromFile(generation.evidence)
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

    private fun missingCommandRoute(application: ConfigurableApplicationContext): HttpResponse<String> {
        val request = HttpRequest
            .newBuilder(uri(application, "/command"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getAdmin(
        application: ConfigurableApplicationContext,
        version: String,
    ): HttpResponse<String> {
        val request = HttpRequest
            .newBuilder(uri(application, "/admin/log"))
            .header("X-Api-Version", version)
            .GET()
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun uri(
        application: ConfigurableApplicationContext,
        path: String,
    ): URI {
        val port = (application as WebServerApplicationContext).webServer.port
        return URI("http://127.0.0.1:$port$path")
    }
}
