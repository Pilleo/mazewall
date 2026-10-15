package demo.vulnapp

import com.fasterxml.jackson.databind.ObjectMapper
import demo.vulnapp.config.ExecutorDrain
import demo.vulnapp.config.IncompleteProfileEvidenceException
import demo.vulnapp.config.MazewallProtectionBootstrap
import demo.vulnapp.config.ProfileArtifactPublisher
import demo.vulnapp.config.ServiceExecutorOwner
import demo.vulnapp.config.ServiceResourceBindings
import demo.vulnapp.config.collectorCoverage
import demo.vulnapp.service.DefaultFileService
import demo.vulnapp.service.FileService
import demo.vulnapp.service.ProxyService
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.profiler.api.BillOfBehavior
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VulnerableWebAppFreshJvmTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `actual servlet multipart staging stays inside the declared upload resource`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "multipartStorageBody")
    }

    @Suppress("unused")
    fun multipartStorageBody() {
        val root = Files.createTempDirectory("web-multipart-storage-")
        val bindings = ServiceResourceBindings(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("uploads")))
        try {
            startApplication(enabled = false, bindings = bindings).use { application ->
                val multipart = application.getBean(jakarta.servlet.MultipartConfigElement::class.java)
                val location = java.nio.file.Path
                    .of(multipart.location)
                    .toAbsolutePath()
                    .normalize()
                assertTrue(location.startsWith(bindings.uploads.toRealPath()), "Servlet staging must use a declared resource, not a per-container scratch path: $location")
                assertTrue(Files.isDirectory(location))
                assertEquals(1_048_576L, multipart.maxFileSize)
                assertEquals(10_485_760L, multipart.maxRequestSize)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `learned network calls cannot relax the explicit service ceiling`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "networkCeilingBody")
    }

    @Suppress("unused")
    fun networkCeilingBody() {
        val root = Files.createTempDirectory("web-network-ceiling-")
        val target = root.resolve("sbob.json")
        val bindings = ServiceResourceBindings(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("uploads")))
        val server = com.sun.net.httpserver.HttpServer
            .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val served = java.util.concurrent.atomic
            .AtomicInteger()
        server.createContext("/") { exchange ->
            served.incrementAndGet()
            val bytes = "owned-network-canary".toByteArray()
            exchange.responseHeaders.add("Connection", "close")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/"
        val owner = ServiceExecutorOwner(java.time.Duration.ofSeconds(5))
        try {
            assertEquals(
                "owned-network-canary",
                demo.vulnapp.service
                .DefaultProxyService()
                .fetchUrl(url),
            )
            served.set(0)
            ProfileArtifactPublisher.publish(
                target,
                BillOfBehavior(opens = setOf(System.getProperty("java.home")), syscalls = setOf(io.mazewall.platform.syscall.Syscall.SOCKET, io.mazewall.platform.syscall.Syscall.CONNECT)),
                ExecutorDrain.Complete,
                listOf(collectorCoverage()),
            )
            System.setProperty("mazewall.sbob.path", target.toString())
            val response = demo.vulnapp.config
                .MazewallConfig(owner, bindings)
                .proxyService()
                .fetchUrl(url)
            assertEquals(0, served.get(), "Profile observation must not authorize a request prohibited by the service ceiling: $response")
            assertTrue(
                response.contains("Operation not permitted") || response.contains("Permission denied") || Regex("\\berror[=:]\\s*(1|13)\\b").containsMatchIn(response),
                "A generic fetch failure is not proof of containment: $response",
            )
        } finally {
            try {
                assertEquals(ExecutorDrain.Complete, owner.stopAdmissionAndDrain())
            } finally {
                System.clearProperty("mazewall.sbob.path")
                server.stop(0)
                root.toFile().deleteRecursively()
            }
        }
    }

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

    @Test
    @EnabledIfLinuxAndSupported
    fun `incomplete profile publication refuses enforcement`() {
        val root = Files.createTempDirectory("mazewall-web-incomplete-profile-")
        try {
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "profileIncompleteEvidenceBody", root.toString())
            val target = root.resolve("sbob.json")
            val complete = ProfileArtifactPublisher.readCurrent(target)
            assertTrue(ObjectMapper().readTree(Files.readString(complete.integrity)).path("complete").asBoolean(), "Actual benign collection must complete before exercising the negative handoff")
            assertEquals(complete.evidence, ProfileArtifactPublisher.resolveEvidencePath(target))
            // The negative control models interrupted retirement, not an accidental collector defect.
            ProfileArtifactPublisher.publish(target, BillOfBehavior.fromFile(complete.evidence), ExecutorDrain.Incomplete.Interrupted, listOf(collectorCoverage()))
            assertTrue(
                ObjectMapper()
                .readTree(Files.readString(ProfileArtifactPublisher.readCurrent(target).integrity))
                .path("reason")
                .asText()
                .contains("interrupted"),
            )
            assertFailsWith<IncompleteProfileEvidenceException> {
                ProfileArtifactPublisher.resolveEvidencePath(root.resolve("sbob.json"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Suppress("unused")
    fun unprotectedBody() {
        val root = Files.createTempDirectory("mazewall-web-unprotected-")
        val bindings = ServiceResourceBindings(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("uploads")))
        val marker = root.resolve("marker")
        Files.writeString(marker, "preserve")
        val created = root.resolve("created")
        try {
            startApplication(enabled = false, bindings = bindings).use { application ->
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
            root.toFile().deleteRecursively()
        }
    }

    @Suppress("unused")
    fun protectedBody() {
        val root = Files.createTempDirectory("mazewall-web-protected-")
        val marker = root.resolve("marker")
        val target = root.resolve("sbob.json")
        val bindings = ServiceResourceBindings(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("uploads")))
        ProfileArtifactPublisher.publish(
            target,
            BillOfBehavior(),
            ExecutorDrain.Complete,
            listOf(collectorCoverage()),
        )
        System.setProperty("mazewall.sbob.path", target.toString())
        try {
            lateinit var owner: ServiceExecutorOwner
            startApplication(enabled = true, bindings = bindings).use { application ->
                owner = application.getBean(ServiceExecutorOwner::class.java)
                assertFalse(application.getBean(FileService::class.java) is DefaultFileService)
                assertEquals("true", application.environment.getProperty(MazewallProtectionBootstrap.RESOLVED_PROPERTY))
                val benign = getAdmin(application, "1.0")
                assertEquals(200, benign.statusCode())
                assertTrue(benign.body().contains("Log processed"))

                val bytes = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("benign.txt"))
                    zip.write("bound upload".toByteArray())
                    zip.closeEntry()
                }
                val upload = org.springframework.mock.web
                    .MockMultipartFile("file", "benign.zip", "application/zip", bytes.toByteArray())
                application.getBean(FileService::class.java).extractZip(upload)
                assertEquals("bound upload", Files.readString(bindings.uploads.resolve("benign.txt")))

                val escape = root.resolve("http-escape")
                val hostile = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(hostile).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("../http-escape"))
                    zip.write("forbidden escape".toByteArray())
                    zip.closeEntry()
                }
                val boundary = "owned-upload-boundary"
                val form = java.io.ByteArrayOutputStream().apply {
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"escape.zip\"\r\nContent-Type: application/zip\r\n\r\n".toByteArray())
                    write(hostile.toByteArray())
                    write("\r\n--$boundary--\r\n".toByteArray())
                }
                val request = HttpRequest
                    .newBuilder(uri(application, "/upload"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(form.toByteArray()))
                    .build()
                val refused = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
                assertEquals(403, refused.statusCode(), "Actual containment denial must reach HTTP without becoming a generic server error: ${refused.body()}")
                assertEquals("INFERRED_PERMISSION_FAILURE", ObjectMapper().readTree(refused.body()).path("evidence").asText())
                assertFalse(Files.exists(escape))
                application.getBean(FileService::class.java).extractZip(
                    org.springframework.mock.web
                    .MockMultipartFile("file", "after.zip", "application/zip", bytes.toByteArray()),
                )
                assertEquals("bound upload", Files.readString(bindings.uploads.resolve("benign.txt")))

                val failure = runCatching {
                    application.getBean(FileService::class.java).runCommand("printf escaped > '$marker'")
                }.exceptionOrNull()
                assertTrue(ContainmentViolationDetector.findViolationCause(requireNotNull(failure)) != null)
                val denied = kotlin.test.assertIs<io.mazewall.enforcer.api.ContainmentViolationException>(failure)
                val nativeCause = kotlin.test.assertIs<java.io.IOException>(denied.cause)
                assertTrue(nativeCause.message.orEmpty().contains("Cannot run program \"sh\""), "denial must be the command launch, not an unrelated resource failure")
                assertTrue(Regex("\\berror[=:]\\s*(1|13)\\b").containsMatchIn(nativeCause.message.orEmpty()), nativeCause.toString())
                assertEquals(io.mazewall.enforcer.api.ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(denied)?.evidence)
                assertFalse(Files.exists(marker), "protected service command must not create its marker")
            }
            assertEquals(ExecutorDrain.Complete, owner.stopAdmissionAndDrain())
        } finally {
            System.clearProperty("mazewall.sbob.path")
            root.toFile().deleteRecursively()
        }
    }

    @Suppress("unused")
    fun profileIncompleteEvidenceBody(rootText: String) {
        val root = java.nio.file.Path
            .of(rootText)
        val target = root.resolve("sbob.json")
        val bindings = ServiceResourceBindings(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("uploads")))
        System.setProperty("mazewall.sbob.path", target.toString())
        check(System.getProperty("mazewall.allow-incomplete-profile-enforcement") != "true") {
            "incomplete enforcement override must stay unset"
        }
        try {
            startApplication(enabled = false, profile = true, sbobPath = target, bindings = bindings).use { application ->
                assertEquals(200, getAdmin(application, "1.0").statusCode())
                val bytes = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("benign.txt"))
                    zip.write("bound upload".toByteArray())
                    zip.closeEntry()
                }
                val upload = org.springframework.mock.web
                    .MockMultipartFile("file", "benign.zip", "application/zip", bytes.toByteArray())
                application.getBean(FileService::class.java).extractZip(upload)
                val fetched = application.getBean(ProxyService::class.java).fetchUrl(uri(application, "/admin/log").toString())
                assertTrue(fetched.contains("Log processed"), "Profile a real owned successful fetch: $fetched")
                assertFalse(Files.exists(target), "current-generation pointer must be published only during shutdown")
            }
        } finally {
            System.clearProperty("mazewall.sbob.path")
        }
    }

    @Suppress("unused")
    fun profileBody() {
        val root = Files.createTempDirectory("mazewall-web-profile-")
        val bindings = ServiceResourceBindings(Files.createDirectory(root.resolve("data")), Files.createDirectory(root.resolve("uploads")))
        val target = root.resolve("sbob.json")
        val marker = root.resolve("profile-marker")
        System.setProperty("mazewall.sbob.path", target.toString())
        try {
            startApplication(enabled = false, profile = true, sbobPath = target, bindings = bindings).use { application ->
                assertFalse(application.getBean(FileService::class.java) is DefaultFileService)
                assertEquals(200, getAdmin(application, "profile-control").statusCode())
                application.getBean(FileService::class.java).runCommand("printf profiled > '$marker'")
                assertEquals("profiled", Files.readString(marker))
                assertFalse(Files.exists(target), "current-generation pointer must be published only during shutdown")
            }

            val generation = ProfileArtifactPublisher.readCurrent(target)
            val evidence = io.mazewall.profiler.api.BillOfBehavior
                .fromFile(generation.evidence)
            assertTrue(
                evidence.execs.isNotEmpty() || evidence.toJson().contains("\"syscalls\""),
                "shutdown must publish observed HTTP service evidence",
            )
            assertTrue(Files.readString(generation.stackDictionary).isNotBlank())
            val integrity = ObjectMapper().readTree(Files.readString(generation.integrity))
            if (integrity.path("complete").booleanValue()) {
                assertEquals(generation.evidence, ProfileArtifactPublisher.resolveEvidencePath(target))
            } else {
                kotlin.test.assertFailsWith<demo.vulnapp.config.IncompleteProfileEvidenceException> {
                    ProfileArtifactPublisher.resolveEvidencePath(target)
                }
            }
        } finally {
            System.clearProperty("mazewall.sbob.path")
            root.toFile().deleteRecursively()
        }
    }

    private fun startApplication(
        enabled: Boolean,
        profile: Boolean = false,
        sbobPath: java.nio.file.Path? = null,
        bindings: ServiceResourceBindings? = null,
    ): ConfigurableApplicationContext {
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
        val application = SpringApplication(VulnAppApplication::class.java)
        application.addListeners(MazewallProtectionBootstrap())
        bindings?.let { acquired ->
            application.addInitializers(
                org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                    context.beanFactory.registerSingleton("serviceResourceBindings", acquired)
                },
            )
        }
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
