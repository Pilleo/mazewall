package io.mazewall.portal.codegen

import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalCodec
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPayload
import io.mazewall.portal.PortalServiceManifestCodec
import io.mazewall.portal.PortalWorkerArtifact
import io.mazewall.portal.WorkerBundleMaterializer
import io.mazewall.portal.codegen.testapi.KotlinDataDtoService
import io.mazewall.portal.codegen.testapi.KotlinJvmRecordDtoService
import io.mazewall.portal.codegen.testapi.MethodIdShadowService
import io.mazewall.portal.worker.PortalWorkerMain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PortalCodegenFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `host only configuration publishes no worker code or descriptor`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.A"), workerSourceSet = null)
        val result = runCatching { runner("build").build() }.getOrElse { failure ->
            throw AssertionError((failure as org.gradle.testkit.runner.UnexpectedBuildFailure).buildResult.output, failure)
        }
        assertEquals(null, result.task(":portalWorkerJar"))
        assertTrue(generatedContents().keys.all { it.name.endsWith("PortalStub.kt") })
        assertFalse(generatedContents().isEmpty())
    }

    @Test
    fun `ordinary build compiles opted in worker sources`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.A"))
        projectDir.resolve("src/portalWorker/kotlin/fixture/Main.kt").writeText(
            "package fixture\nfun brokenGuest(): String = workerCompilationMustRun()\n",
        )

        val result = runner("build").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":compilePortalWorkerKotlin")?.outcome)
        assertTrue(result.output.contains("workerCompilationMustRun"), result.output)
    }

    @Test
    fun `Java only consumer compiles generated portal sources without applying Kotlin`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                KotlinDataDtoService::class.java.name,
                KotlinJvmRecordDtoService::class.java.name,
            ),
            applyKotlinPlugin = false,
        )

        val result = runner("build", "plainJavaPortalExample").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compilePortalWorkerKotlin")?.outcome)
        assertTrue(result.output.contains("PLAIN_JAVA_PORTAL_EXAMPLE_OK"), result.output)
    }

    @Test
    fun `worker bundle packages the generated worker jar beneath lib`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                "fixture.StringCollection",
                KotlinDataDtoService::class.java.name,
                KotlinJvmRecordDtoService::class.java.name,
                MethodIdShadowService::class.java.name,
            ),
            compileGenerated = true,
        )

        val result = runner("portalWorkerBundle").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":portalWorkerBundle")?.outcome)
        val bundle = projectDir.resolve("build/libs/portal-codegen-fixture-portal-worker.zip")
        val digest = bundle.resolveSibling("${bundle.fileName}.sha256")
        java.util.zip.ZipFile(bundle.toFile()).use { zip ->
            assertTrue(
                zip
                .entries()
                .asSequence()
                .map { it.name }
                .any { it.startsWith("lib/") && it.endsWith(".jar") },
            )
            assertTrue(zip.entries().asSequence().none { it.name.startsWith("src/") || it.name.endsWith(".class") })
            val bootstrapJar =
                zip
                .entries()
                .asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".jar") }
                .map { entry ->
                    zip.getInputStream(entry).use { bytes ->
                        java.util.jar.JarInputStream(bytes).use { jar ->
                        generateSequence { jar.nextJarEntry }.any { it.name == "io/mazewall/portal/worker/PortalWorkerMain.class" }
                    }
                    }
                }.any { it }
            assertTrue(bootstrapJar, "worker bundle must include PortalWorkerMain")
        }
        assertTrue(digest.exists())
        assertTrue(digest.readText().matches(Regex("[0-9a-f]{64}\\n")))
    }

    @Test
    fun `verified worker bundle launches from a directory outside its project`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                KotlinDataDtoService::class.java.name,
                KotlinJvmRecordDtoService::class.java.name,
            ),
            compileGenerated = true,
        )
        runner("portalWorkerBundle").build()
        val bundle = projectDir.resolve("build/libs/portal-codegen-fixture-portal-worker.zip")
        val extracted = WorkerBundleMaterializer.materialize(
            PortalWorkerArtifact(bundle, sha256(bundle)),
            Files.createTempDirectory("portal-worker-cache"),
        )
        val freshDirectory = Files.createTempDirectory("portal-worker-fresh-directory")
        val socketPath = freshDirectory.resolve("portal.sock")
        val server = RealSocketManager.createUnixServer(socketPath.toString())
        val coldStartNanos = System.nanoTime()
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                WorkerBundleMaterializer.classpath(extracted.directory),
                PortalWorkerMain::class.java.name,
                socketPath.toString(),
            ).directory(freshDirectory.toFile()).start()
        try {
            val peer = RealSocketManager.accept(server)
            val channel = PortalChannel(peer, RealSocketManager)
            try {
                assertEquals(PortalWorkerMain.READY, process.inputStream.bufferedReader().readLine())
                val coldStartMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - coldStartNanos)
                val payload = PortalCodec.encodeString("plain-java-example")
                val warmStartNanos = System.nanoTime()
                channel.send(PortalFrame(PortalKind.Request, 1, PortalMethod.Echo, PortalPayload(payload), 0))
                val (reply, capabilities) = channel.receive(10_000)
                assertTrue(capabilities.isEmpty())
                assertEquals("plain-java-example", PortalCodec.Reader(reply.payload.copyToByteArray()).string())
                val warmCallMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - warmStartNanos)
                val residentBytes = Files
                    .readString(Path.of("/proc", process.pid().toString(), "status"))
                    .lineSequence()
                    .first { it.startsWith("VmRSS:") }
                    .trim()
                    .split(Regex("\\s+"))[1]
                    .toLong() *
                    1024L
                assertTrue(coldStartMillis >= 0)
                assertTrue(warmCallMicros >= 0)
                assertTrue(payload.isNotEmpty())
                assertTrue(residentBytes > 0)
                println("PORTAL_MEASUREMENTS cold_start_ms=$coldStartMillis warm_call_us=$warmCallMicros payload_bytes=${payload.size} worker_rss_bytes=$residentBytes")
            } finally {
                channel.close()
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        } finally {
            RealSocketManager.close(server)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `portal check validates the worker bundle for opted in projects`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                "fixture.StringCollection",
                KotlinDataDtoService::class.java.name,
                KotlinJvmRecordDtoService::class.java.name,
                MethodIdShadowService::class.java.name,
            ),
            compileGenerated = true,
        )

        val result = runner("portalCheck").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":portalCheck")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":portalWorkerBundle")?.outcome)
    }

    @Test
    fun `main source set cannot be selected as worker output`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.A"), workerSourceSet = "main")
        val result = runner("generatePortalStubs").buildAndFail()
        assertTrue(result.output.contains("dedicated worker source set"), result.output)
    }

    @Test
    fun `generated sources compile and round trip Java and Kotlin DTOs`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                "fixture.StringCollection",
                KotlinDataDtoService::class.java.name,
                KotlinJvmRecordDtoService::class.java.name,
                MethodIdShadowService::class.java.name,
            ),
            compileGenerated = true,
        )

        val result = runner("build", "roundTrip").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":portalWorkerJar")?.outcome)
        assertTrue(result.output.contains("ROUND_TRIP_OK"), result.output)
        val jars = Files.list(projectDir.resolve("build/libs")).use { it.toList() }
        val host = jars.single { !it.fileName.toString().contains("portal-worker") }
        java.util.jar.JarFile(host.toFile()).use { jar ->
            assertFalse(
                jar
                .entries()
                .asSequence()
                .any { it.name.endsWith("PortalDispatcher.class") || it.name.startsWith("META-INF/mazewall/portal/services/") },
            )
        }
        val worker = jars.single { it.fileName.toString().contains("portal-worker") && it.fileName.toString().endsWith(".jar") }
        java.util.jar.JarFile(worker.toFile()).use { jar ->
            assertTrue(jar.entries().asSequence().any { it.name == "fixture/APortalDispatcher.class" })
            assertTrue(jar.getEntry("META-INF/mazewall/portal/fixture.A") != null)
        }
    }

    @Test
    fun `generation synchronizes A B to A to empty without stale outputs`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.A", "fixture.B"))
        runner("generatePortalStubs").build()
        assertGeneratedServices(setOf("A", "B"))

        writeFixture(apiJar, listOf("fixture.A"))
        runner("generatePortalStubs", "--rerun-tasks").build()
        assertGeneratedServices(setOf("A"))

        writeFixture(apiJar, emptyList())
        runner("generatePortalStubs", "--rerun-tasks").build()
        assertGeneratedServices(emptySet())
    }

    @Test
    fun `schema failure leaves both previously published trees unchanged`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.A", "fixture.B"))
        runner("generatePortalStubs").build()
        val before = generatedContents()

        writeFixture(apiJar, listOf("fixture.A", "fixture.Missing"))
        runner("generatePortalStubs", "--rerun-tasks").buildAndFail()

        assertEquals(before, generatedContents())
    }

    @Test
    fun `generation rejects nested artifacts reserved by a legal top level service`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.CollisionOuter\$Service",
                "fixture.Nested_14_CollisionOuter_7_Service",
            ),
        )

        val result = runner("generatePortalStubs").buildAndFail()

        assertTrue(result.output.contains("portal services produce colliding generated names"), result.output)
        assertTrue(generatedContents().isEmpty(), generatedContents().toString())
    }

    @Test
    fun `registered service publishes identical host and worker manifests`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                KotlinDataDtoService::class.java.name,
                KotlinJvmRecordDtoService::class.java.name,
                MethodIdShadowService::class.java.name,
            ),
            compileGenerated = true,
            registration =
                PortalServiceRegistration(
                    serviceId = "fixture-a",
                    contractName = "fixture.A",
                    implementationName = "fixture.AImpl",
                    isolationGroup = "fixture",
                    instanceMode = io.mazewall.portal.PortalInstanceMode.STATEFUL_SINGLETON,
                    policyProfile = "restricted",
                    workerArtifact = "fixture.portal-worker.jar",
                ),
        )

        val result = runner("generatePortalStubs").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generatePortalStubs")?.outcome)

        val host = projectDir.resolve("build/generated/portal-manifests/META-INF/mazewall/portal/services/fixture-a").readText()
        val worker = projectDir.resolve("build/generated/portal-worker-resources/META-INF/mazewall/portal/services/fixture-a").readText()
        assertEquals(host, worker)
        assertEquals(
            "fixture-a\n",
            projectDir.resolve("build/generated/portal-manifests/META-INF/mazewall/portal/services.index").readText(),
        )
        assertEquals("fixture.AImpl", PortalServiceManifestCodec.decode(host).implementationName)
    }

    @Test
    fun `registered service publishes verified worker bundle metadata for the host`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                KotlinDataDtoService::class.java.name,
                KotlinJvmRecordDtoService::class.java.name,
                MethodIdShadowService::class.java.name,
            ),
            compileGenerated = true,
            registration =
                PortalServiceRegistration(
                    serviceId = "fixture-a",
                    contractName = "fixture.A",
                    implementationName = "fixture.AImpl",
                    isolationGroup = "fixture",
                    instanceMode = io.mazewall.portal.PortalInstanceMode.STATELESS,
                    policyProfile = "restricted",
                    workerArtifact = "fixture-worker",
                ),
        )

        val result = runner("publishPortalWorkerArtifactMetadata").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":publishPortalWorkerArtifactMetadata")?.outcome)
        val metadata =
            projectDir.resolve(
                "build/generated/portal-worker-artifacts/META-INF/mazewall/portal/worker-artifacts/fixture-worker",
            )
        assertTrue(metadata.readText().contains("sha256="), metadata.readText())
        assertEquals(
            "fixture-worker\n",
            projectDir
                .resolve(
                "build/generated/portal-worker-artifacts/META-INF/mazewall/portal/worker-artifacts.index",
            ).readText(),
        )
    }

    private fun writeFixture(
        apiJar: Path,
        services: List<String>,
        compileGenerated: Boolean = false,
        workerSourceSet: String? = "portalWorker",
        registration: PortalServiceRegistration? = null,
        applyKotlinPlugin: Boolean = true,
    ) {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"portal-codegen-fixture\"\n")
        val portalJar = Path.of(System.getProperty("portal.codegen.portalJar")).toAbsolutePath()
        val platformJar = Path.of(System.getProperty("portal.codegen.platformJar")).toAbsolutePath()
        val enforcerJar = Path.of(System.getProperty("portal.codegen.enforcerJar")).toAbsolutePath()
        val testApiClasses =
            Path
                .of(
                    KotlinDataDtoService::class.java.protectionDomain.codeSource.location
                    .toURI(),
                ).toAbsolutePath()
        val javaTestApiClasses =
            Path
                .of(
                    MethodIdShadowService::class.java.protectionDomain.codeSource.location
                    .toURI(),
                ).toAbsolutePath()
        val quotedServices = services.joinToString(", ") { service ->
            val escaped = service.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")
            "\"$escaped\""
        }
        val extra =
            if (compileGenerated) {
                """
                tasks.register<JavaExec>("roundTrip") {
                    dependsOn("portalWorkerClasses")
                    classpath = sourceSets["portalWorker"].runtimeClasspath
                    mainClass.set("fixture.MainKt")
                }
                """.trimIndent()
            } else {
                ""
            }
        val plainJavaExampleTask =
            if (!applyKotlinPlugin) {
                """
                tasks.register<JavaExec>("plainJavaPortalExample") {
                    dependsOn("classes")
                    classpath = sourceSets["main"].runtimeClasspath
                    mainClass.set("fixture.PlainJavaPortalExample")
                }
                """.trimIndent()
            } else {
                ""
            }
        val registrationDsl =
            registration
                ?.let {
                """
                registrations.set(
                    listOf(
                        io.mazewall.portal.codegen.PortalServiceRegistration(
                            serviceId = "${it.serviceId}",
                            contractName = "${it.contractName}",
                            implementationName = "${it.implementationName}",
                            isolationGroup = "${it.isolationGroup}",
                            instanceMode = io.mazewall.portal.PortalInstanceMode.${it.instanceMode.name},
                            policyProfile = "${it.policyProfile}",
                            workerArtifact = "${it.workerArtifact}",
                        ),
                    ),
                )
                """.trimIndent()
            }.orEmpty()
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                ${if (applyKotlinPlugin) "kotlin(\"jvm\") version \"2.4.20-Beta1\"" else "java"}
                id("io.mazewall.portal.codegen")
            }
            repositories { mavenCentral() }
            dependencies {
                implementation(files(
                    "${apiJar.toAbsolutePath()}", "$portalJar", "$platformJar", "$enforcerJar",
                    "$testApiClasses", "$javaTestApiClasses",
                ))
            }
            portalCodegen {
                interfaces.set(listOf($quotedServices))
                ${workerSourceSet?.let { "workerSourceSet.set(\"$it\")" }.orEmpty()}
                $registrationDsl
            }
            $extra
            $plainJavaExampleTask
            """.trimIndent(),
        )
        val javaSourceDir = projectDir.resolve("src/main/java/fixture").createDirectories()
        javaSourceDir.resolve("PlainJavaPortalExample.java").writeText(
            """
            package fixture;

            import io.mazewall.portal.PortalCodec;

            public final class PlainJavaPortalExample {
                public static void main(String[] args) {
                    byte[] payload = PortalCodec.INSTANCE.encodeString("plain-java-portal");
                    String decoded = new PortalCodec.Reader(payload).string();
                    if (!"plain-java-portal".equals(decoded)) throw new AssertionError(decoded);
                    try {
                        Class.forName("fixture.APortalStub");
                    } catch (ClassNotFoundException failure) {
                        throw new AssertionError("generated portal stub was not compiled", failure);
                    }
                    System.out.println("PLAIN_JAVA_PORTAL_EXAMPLE_OK");
                }
            }
            """.trimIndent(),
        )
        val sourceDir = projectDir.resolve("src/portalWorker/kotlin/fixture").createDirectories()
        sourceDir.resolve("Main.kt").writeText(
            """
            package fixture
            import io.mazewall.portal.codegen.testapi.KotlinDataDto
            import io.mazewall.portal.codegen.testapi.KotlinDataDtoService
            import io.mazewall.portal.codegen.testapi.KotlinDataDtoServicePortalDispatcher
            import io.mazewall.portal.codegen.testapi.KotlinJvmRecordDto
            import io.mazewall.portal.codegen.testapi.KotlinJvmRecordDtoService
            import io.mazewall.portal.codegen.testapi.KotlinJvmRecordDtoServicePortalDispatcher
            import io.mazewall.portal.PortalCodec
            import io.mazewall.portal.PortalMethod
            private fun methodId(name: String, parameterTypes: String): Int =
                (("fixture.A#" + name + "#" + parameterTypes).hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000
            fun main() {
                val implementation = object : A {
                    override fun inherited(value: String): String = "inherited:" + value
                    override fun overridden(value: Int): Int = value + 1
                    override fun diamond(value: String): String = "diamond:" + value
                    override fun payload(payload: String, reader: String, result: String, impl: String, method: String, granted: String, broker: String) =
                        payload + reader + result + impl + method + granted + broker
                    override fun ping(value: Int): Int = value
                    override fun PING(value: String): String = value
                    override fun echoPojo(value: SamplePojo): SamplePojo = SamplePojo(value.getName() + "!", value.count() + 1)
                    override fun echoNestedRecord(value: NestedDtos.NestedRecord): NestedDtos.NestedRecord =
                        NestedDtos.NestedRecord(value.name() + "!", value.count() + 1)
                    override fun echoNestedPojo(value: NestedDtos.NestedPojo): NestedDtos.NestedPojo =
                        NestedDtos.NestedPojo(value.getName() + "!", value.count() + 1)
                    override fun countValues(values: MutableList<String>): MutableMap<String, Int> = values.associateWithTo(mutableMapOf(), String::length)
                }
                val inherited = APortalDispatcher.handle(
                    implementation,
                    PortalMethod.Generated(methodId("inherited", "java.lang.String")),
                    PortalCodec.encodeString("safe"),
                    emptyList(),
                )
                check(PortalCodec.Reader(inherited).string() == "inherited:safe")
                val diamond = APortalDispatcher.handle(
                    implementation,
                    PortalMethod.Generated(methodId("diamond", "java.lang.String")),
                    PortalCodec.encodeString("safe"),
                    emptyList(),
                )
                check(PortalCodec.Reader(diamond).string() == "diamond:safe")
                val pojoPayload = PortalCodec.concat(listOf(PortalCodec.encodeString("safe"), PortalCodec.encodeInt(4)))
                val pojo = APortalDispatcher.handle(
                    implementation,
                    PortalMethod.Generated(methodId("echoPojo", "fixture.SamplePojo")),
                    pojoPayload,
                    emptyList(),
                )
                val reader = PortalCodec.Reader(pojo)
                check(reader.string() == "safe!")
                check(reader.int() == 5)
                val nestedRecord = APortalDispatcher.handle(
                    implementation,
                    PortalMethod.Generated(methodId("echoNestedRecord", "fixture.NestedDtos\${'$'}NestedRecord")),
                    pojoPayload,
                    emptyList(),
                )
                val nestedRecordReader = PortalCodec.Reader(nestedRecord)
                check(nestedRecordReader.string() == "safe!")
                check(nestedRecordReader.int() == 5)
                val nestedPojo = APortalDispatcher.handle(
                    implementation,
                    PortalMethod.Generated(methodId("echoNestedPojo", "fixture.NestedDtos\${'$'}NestedPojo")),
                    pojoPayload,
                    emptyList(),
                )
                val nestedPojoReader = PortalCodec.Reader(nestedPojo)
                check(nestedPojoReader.string() == "safe!")
                check(nestedPojoReader.int() == 5)
                val collectionPayload = PortalCodec.concat(
                    listOf(
                        PortalCodec.encodeInt(2),
                        PortalCodec.encodeString("one"),
                        PortalCodec.encodeString("four"),
                    ),
                )
                val countedValues = APortalDispatcher.handle(
                    implementation,
                    PortalMethod.Generated(methodId("countValues", "java.util.List")),
                    collectionPayload,
                    emptyList(),
                )
                val countedValuesReader = PortalCodec.Reader(countedValues)
                check(countedValuesReader.collectionCount() == 2)
                check(countedValuesReader.string() == "one")
                check(countedValuesReader.int() == 3)
                check(countedValuesReader.string() == "four")
                check(countedValuesReader.int() == 4)
                val kotlinImplementation = object : KotlinDataDtoService {
                    override fun echoKotlin(value: KotlinDataDto): KotlinDataDto =
                        KotlinDataDto(value.name + "!", value.count + 1)
                }
                val kotlinDto = KotlinDataDtoServicePortalDispatcher.handle(
                    kotlinImplementation,
                    PortalMethod.Generated(
                        (("io.mazewall.portal.codegen.testapi.KotlinDataDtoService#echoKotlin#" +
                            "io.mazewall.portal.codegen.testapi.KotlinDataDto").hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000,
                    ),
                    pojoPayload,
                    emptyList(),
                )
                val kotlinReader = PortalCodec.Reader(kotlinDto)
                check(kotlinReader.string() == "safe!")
                check(kotlinReader.int() == 5)
                val recordImplementation = object : KotlinJvmRecordDtoService {
                    override fun echoKotlinJvmRecord(value: KotlinJvmRecordDto) = KotlinJvmRecordDto(value.name + "!", value.count + 1)
                }
                val recordResult = KotlinJvmRecordDtoServicePortalDispatcher.handle(
                    recordImplementation,
                    PortalMethod.Generated(
                        (("io.mazewall.portal.codegen.testapi.KotlinJvmRecordDtoService#echoKotlinJvmRecord#" +
                            "io.mazewall.portal.codegen.testapi.KotlinJvmRecordDto").hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000,
                    ),
                    pojoPayload,
                    emptyList(),
                )
                val recordReader = PortalCodec.Reader(recordResult)
                check(recordReader.string() == "safe!")
                check(recordReader.int() == 5)
                println("ROUND_TRIP_OK")
            }
            """.trimIndent(),
        )
    }

    private fun compileApiJar(root: Path): Path {
        val sources = root.resolve("api-src/fixture").createDirectories()
        sources.resolve("A.java").writeText(
            """
            package fixture;
            public interface A extends Base, DiamondLeft, DiamondRight {
                @Override int overridden(int value);
                String payload(String payload, String reader, String result, String impl, String method, String granted, String broker);
                int ping(int value);
                String PING(String value);
                SamplePojo echoPojo(SamplePojo value);
                NestedDtos.NestedRecord echoNestedRecord(NestedDtos.NestedRecord value);
                NestedDtos.NestedPojo echoNestedPojo(NestedDtos.NestedPojo value);
                java.util.Map<String, Integer> countValues(java.util.List<String> values);
            }
            """.trimIndent(),
        )
        sources.resolve("DiamondLeft.java").writeText(
            "package fixture; public interface DiamondLeft { String diamond(String value); }",
        )
        sources.resolve("DiamondRight.java").writeText(
            "package fixture; public interface DiamondRight { String diamond(String value); }",
        )
        sources.resolve("B.java").writeText(
            "package fixture; public interface B extends A {}",
        )
        sources.resolve("GenericCollection.java").writeText(
            "package fixture; public interface GenericCollection<T> { java.util.Map<String, T> transform(java.util.List<T> values); }",
        )
        sources.resolve("StringCollection.java").writeText(
            "package fixture; public interface StringCollection extends GenericCollection<String> {}",
        )
        sources.resolve("Base.java").writeText(
            "package fixture; public interface Base { String inherited(String value); int overridden(int value); }",
        )
        sources.resolve("SamplePojo.java").writeText(
            """
            package fixture;
            public final class SamplePojo {
                private final String name;
                private final int count;
                public SamplePojo(String name, int count) { this.name = name; this.count = count; }
                public String getName() { return name; }
                public int count() { return count; }
            }
            """.trimIndent(),
        )
        sources.resolve("NestedDtos.java").writeText(
            """
            package fixture;
            public final class NestedDtos {
                private NestedDtos() {}
                public record NestedRecord(String name, int count) {}
                public static final class NestedPojo {
                    private final String name;
                    private final int count;
                    public NestedPojo(String name, int count) { this.name = name; this.count = count; }
                    public String getName() { return name; }
                    public int count() { return count; }
                }
            }
            """.trimIndent(),
        )
        sources.resolve("CollisionOuter.java").writeText(
            "package fixture; public final class CollisionOuter { public interface Service { String echo(String value); } }",
        )
        sources.resolve("Nested_14_CollisionOuter_7_Service.java").writeText(
            "package fixture; public interface Nested_14_CollisionOuter_7_Service { String echo(String value); }",
        )
        val classes = root.resolve("api-classes").createDirectories()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val exit =
            compiler.run(
                null,
                null,
                null,
                "-parameters",
                "-d",
                classes.toString(),
                sources.resolve("A.java").toString(),
                sources.resolve("B.java").toString(),
                sources.resolve("GenericCollection.java").toString(),
                sources.resolve("StringCollection.java").toString(),
                sources.resolve("Base.java").toString(),
                sources.resolve("DiamondLeft.java").toString(),
                sources.resolve("DiamondRight.java").toString(),
                sources.resolve("SamplePojo.java").toString(),
                sources.resolve("NestedDtos.java").toString(),
                sources.resolve("CollisionOuter.java").toString(),
                sources.resolve("Nested_14_CollisionOuter_7_Service.java").toString(),
            )
        assertEquals(0, exit)
        val jar = root.resolve("api.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    val entry = classes.relativize(file).toString().replace('\\', '/')
                    output.putNextEntry(JarEntry(entry))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
        }
        return jar
    }

    private fun sha256(file: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }

    private fun assertGeneratedServices(expected: Set<String>) {
        val files = generatedContents().keys.map { it.name }.toSet()
        val expectedFiles = expected.flatMap { listOf("${it}PortalStub.kt", "${it}PortalDispatcher.kt", "fixture.$it") }.toSet()
        assertEquals(expectedFiles, files)
        assertFalse(files.any { it.contains("Missing") })
    }

    private fun generatedContents(): Map<Path, String> {
        val root = projectDir.resolve("build/generated")
        if (!root.exists()) return emptyMap()
        return Files.walk(root).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter(Files::isRegularFile)
                .associateWith { it.readText() }
        }
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(*arrayOf("--stacktrace", *arguments))
}
