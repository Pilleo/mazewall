package io.mazewall.portal.codegen

import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.process.JvmLaunchPreparation
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalChannel
import io.mazewall.portal.PortalCodec
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalGenerationMeasurement
import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalMalformedPayloadException
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalParentAuthorization
import io.mazewall.portal.PortalPayload
import io.mazewall.portal.PortalRequestWriter
import io.mazewall.portal.PortalRuntimeLimits
import io.mazewall.portal.PortalServiceManifestCodec
import io.mazewall.portal.PortalWorkerArtifact
import io.mazewall.portal.PortalWorkerGeneration
import io.mazewall.portal.PortalWorkerLaunch
import io.mazewall.portal.PortalWorkerReady
import io.mazewall.portal.ProcessBroker
import io.mazewall.portal.ProcessBrokerLifecycle
import io.mazewall.portal.WorkerBundleMaterializer
import io.mazewall.portal.codegen.testapi.BinaryAndCollectionService
import io.mazewall.portal.codegen.testapi.KotlinDataDtoService
import io.mazewall.portal.codegen.testapi.KotlinJvmRecordDtoService
import io.mazewall.portal.codegen.testapi.MethodIdShadowService
import io.mazewall.portal.codegen.testapi.StrictContractService
import io.mazewall.portal.codegen.testapi.StrictDto
import io.mazewall.portal.openGrantedRead
import io.mazewall.portal.worker.PortalWorkerMain
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
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
    fun `independent fixture copies reuse compilation while changed guest bytes rebuild the bundle`() {
        val home = System.getProperty("mazewall.test.gradleUserHome")
        assertTrue(home != null, "Portal TestKit must use the cache-owned Gradle home")
        assertEquals(Path.of(home).resolve("caches/mazewall-test-kit"), Path.of(System.getProperty("org.gradle.testkit.dir")))
        val fixtureRoot = projectDir
        val cache = fixtureRoot.resolve("cache")
        var previousBundle: String? = null
        for (copy in listOf("first", "second")) {
            projectDir = fixtureRoot.resolve(copy).createDirectories()
            val api = compileApiJar(projectDir)
            writeFixture(
                api,
                listOf("fixture.A", KotlinDataDtoService::class.java.name, KotlinJvmRecordDtoService::class.java.name),
                compileGenerated = true,
            )
            projectDir.resolve("settings.gradle.kts").toFile().appendText("\nbuildCache { local { directory = file(\"$cache\") } }\n")
            val built = runner("classes").build()
            if (copy == "second") {
                for (task in listOf(":compileKotlin", ":compilePortalWorkerKotlin", ":generatePortalStubs", ":portalWorkerBundle")) {
                    assertEquals(TaskOutcome.FROM_CACHE, built.task(task)?.outcome, built.output)
                }
            }
            val bundle = sha256(projectDir.resolve("build/libs/portal-codegen-fixture-portal-worker.zip"))
            if (previousBundle != null) assertEquals(previousBundle, bundle)
            previousBundle = bundle
        }
        val guest = projectDir.resolve("src/portalWorker/kotlin/fixture/Main.kt")
        guest.writeText(guest.readText().replace("ROUND_TRIP_OK", "CHANGED_ROUND_TRIP_OK"))
        val changed = runner("classes").build()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":compilePortalWorkerKotlin")?.outcome, changed.output)
        assertFalse(previousBundle == sha256(projectDir.resolve("build/libs/portal-codegen-fixture-portal-worker.zip")))
    }

    @Test
    fun `plugin consumer classpath resolves aligned kotlin reflect`() {
        val metadata = java.util.Properties()
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties"))
        resource.use(metadata::load)
        val jars = requireNotNull(metadata.getProperty("implementation-classpath"))
            .split(java.io.File.pathSeparatorChar)
            .map { Path.of(it).fileName.toString() }
            .filter { it.startsWith("kotlin-reflect-") && it.endsWith(".jar") }
        assertEquals(listOf("kotlin-reflect-2.4.21.jar"), jars)
    }

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

        assertBuilt(result, ":compileKotlin")
        assertBuilt(result, ":compilePortalWorkerKotlin")
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

        assertBuilt(result, ":portalWorkerBundle")
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
        val workerErrors = freshDirectory.resolve("worker.stderr")
        val coldStartNanos = System.nanoTime()
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                WorkerBundleMaterializer.classpath(extracted.directory),
                PortalWorkerMain::class.java.name,
                socketPath.toString(),
            ).directory(freshDirectory.toFile()).redirectError(workerErrors.toFile()).start()
        try {
            val peer = RealSocketManager.accept(server)
            val channel = PortalChannel(peer, RealSocketManager)
            try {
                assertEquals(PortalWorkerMain.READY, process.inputStream.bufferedReader().readLine(), Files.readString(workerErrors))
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
        assertBuilt(result, ":portalWorkerBundle")
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
                io.mazewall.portal.codegen.testapi.NamesRecordService::class.java.name,
            ),
            compileGenerated = true,
        )

        val result = runner("build", "roundTrip").build()

        assertBuilt(result, ":compileKotlin")
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
    fun `generated sources round trip primitive and boxed Boolean is accessors`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.BooleanPojoService"), compileGenerated = true)
        projectDir.resolve("src/portalWorker/kotlin/fixture/Main.kt").writeText(
            """
            package fixture
            import io.mazewall.portal.PortalCodec
            import io.mazewall.portal.PortalMethod
            fun main() {
                val implementation = object : BooleanPojoService {
                    override fun invert(value: BooleanPojo) =
                        BooleanPojo(!value.isPrimitiveEnabled(), !value.isBoxedEnabled())
                }
                val method = PortalMethod.Generated(
                    (("fixture.BooleanPojoService#invert#fixture.BooleanPojo").hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000,
                )
                for (value in listOf(false, true)) {
                    val payload = PortalCodec.concat(listOf(PortalCodec.encodeBoolean(value), PortalCodec.encodeBoolean(!value)))
                    val response = BooleanPojoServicePortalDispatcher.handle(implementation, method, payload, emptyList())
                    val reader = PortalCodec.Reader(response)
                    check(reader.boolean() == !value)
                    check(reader.boolean() == value)
                }
                println("BOOLEAN_IS_ACCESSORS_OK")
            }
            """.trimIndent(),
        )

        val result = runner("roundTrip").build()

        assertBuilt(result, ":compilePortalWorkerKotlin")
        assertTrue(result.output.contains("BOOLEAN_IS_ACCESSORS_OK"), result.output)
    }

    @Test
    fun `generated byte array and list contracts compile and round trip empty and nonempty values`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf(
                "fixture.A",
                "fixture.ArrayAndListService",
                "fixture.StringCollection",
                BinaryAndCollectionService::class.java.name,
            ),
            compileGenerated = true,
        )
        projectDir.resolve("src/main/kotlin/fixture").createDirectories()
        projectDir.resolve("src/main/kotlin/fixture/HostCompileCheck.kt").writeText(
            """
            package fixture
            import io.mazewall.portal.codegen.testapi.BinaryAndCollectionService
            import io.mazewall.portal.codegen.testapi.BinaryAndCollectionServicePortalStub
            fun verifyHostStubSignatures(
                javaStub: ArrayAndListServicePortalStub,
                kotlinStub: BinaryAndCollectionServicePortalStub,
                genericStub: StringCollectionPortalStub,
            ): List<Any> =
                listOf(
                    { j: ArrayAndListService -> j.echo(byteArrayOf(1)) to j.many() }(javaStub),
                    { k: BinaryAndCollectionService ->
                        k.echoNested(mapOf("a" to k.echoMutable(k.echoReadOnly(listOf(k.echoBytes(byteArrayOf(2)))).toMutableList())))
                    }(kotlinStub),
                    { g: StringCollection -> g.transform(listOf("x")) }(genericStub),
                )
            """.trimIndent(),
        )
        projectDir.resolve("src/portalWorker/kotlin/fixture/Main.kt").writeText(
            """
            package fixture
            import io.mazewall.portal.PortalCodec
            import io.mazewall.portal.PortalMethod
            import io.mazewall.portal.codegen.testapi.BinaryAndCollectionService
            import io.mazewall.portal.codegen.testapi.BinaryAndCollectionServicePortalDispatcher
            private fun idFor(service: String, name: String, params: String): PortalMethod.Generated =
                PortalMethod.Generated(((service + "#" + name + "#" + params).hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000)
            fun main() {
                val javaImpl = object : ArrayAndListService {
                    override fun echo(value: ByteArray): ByteArray = value.copyOf()
                    override fun many(): List<Int> = listOf(10, 20)
                }
                val emptyBytes = ArrayAndListServicePortalDispatcher.handle(
                    javaImpl,
                    idFor("fixture.ArrayAndListService", "echo", "[B"),
                    PortalCodec.encodeBytes(ByteArray(0)),
                    emptyList(),
                )
                check(PortalCodec.Reader(emptyBytes).bytes().isEmpty())
                val nonEmptyBytes = ArrayAndListServicePortalDispatcher.handle(
                    javaImpl,
                    idFor("fixture.ArrayAndListService", "echo", "[B"),
                    PortalCodec.encodeBytes(byteArrayOf(1, 2, 3)),
                    emptyList(),
                )
                check(PortalCodec.Reader(nonEmptyBytes).bytes().contentEquals(byteArrayOf(1, 2, 3)))
                val intList = ArrayAndListServicePortalDispatcher.handle(
                    javaImpl,
                    idFor("fixture.ArrayAndListService", "many", ""),
                    ByteArray(0),
                    emptyList(),
                )
                val listReader = PortalCodec.Reader(intList)
                check(listReader.collectionCount() == 2)
                check(listReader.int() == 10 && listReader.int() == 20)

                val kotlinImpl = object : BinaryAndCollectionService {
                    override fun echoBytes(value: ByteArray): ByteArray = value.reversedArray()
                    override fun echoReadOnly(values: List<ByteArray>): List<ByteArray> = values
                    override fun echoMutable(values: MutableList<ByteArray>): MutableList<ByteArray> = values
                    override fun echoNested(values: Map<String, List<ByteArray>>): MutableMap<String, MutableList<ByteArray>> =
                        values.mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
                }
                val serviceName = BinaryAndCollectionService::class.java.name
                val roPayload = PortalCodec.concat(
                    listOf(PortalCodec.encodeInt(2), PortalCodec.encodeBytes(ByteArray(0)), PortalCodec.encodeBytes(byteArrayOf(7, 8))),
                )
                val roReply = BinaryAndCollectionServicePortalDispatcher.handle(
                    kotlinImpl,
                    idFor(serviceName, "echoReadOnly", "java.util.List"),
                    roPayload,
                    emptyList(),
                )
                val roReader = PortalCodec.Reader(roReply)
                check(roReader.collectionCount() == 2)
                check(roReader.bytes().isEmpty() && roReader.bytes().contentEquals(byteArrayOf(7, 8)))

                val emptyMutableReply = BinaryAndCollectionServicePortalDispatcher.handle(
                    kotlinImpl,
                    idFor(serviceName, "echoMutable", "java.util.List"),
                    PortalCodec.encodeInt(0),
                    emptyList(),
                )
                check(PortalCodec.Reader(emptyMutableReply).collectionCount() == 0)

                val nestedPayload = PortalCodec.concat(
                    listOf(
                        PortalCodec.encodeInt(1),
                        PortalCodec.encodeString("k"),
                        PortalCodec.encodeInt(1),
                        PortalCodec.encodeBytes(byteArrayOf(9)),
                    ),
                )
                val nestedReply = BinaryAndCollectionServicePortalDispatcher.handle(
                    kotlinImpl,
                    idFor(serviceName, "echoNested", "java.util.Map"),
                    nestedPayload,
                    emptyList(),
                )
                val nestedReader = PortalCodec.Reader(nestedReply)
                check(nestedReader.collectionCount() == 1)
                check(nestedReader.string() == "k")
                check(nestedReader.collectionCount() == 1)
                check(nestedReader.bytes().contentEquals(byteArrayOf(9)))
                println("ARRAY_AND_LIST_OK")
            }
            """.trimIndent(),
        )

        val result = runner("build", "roundTrip").build()

        assertBuilt(result, ":compileKotlin")
        assertBuilt(result, ":compilePortalWorkerKotlin")
        assertTrue(result.output.contains("ARRAY_AND_LIST_OK"), result.output)
    }

    @Test
    fun `generatePortalStubs rejects overlapping destination directories before mutating previous output`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.A"))
        runner("generatePortalStubs").build()
        val before = generatedContents()

        projectDir.resolve("build.gradle.kts").toFile().appendText(
            """

            tasks.named<io.mazewall.portal.codegen.GeneratePortalStubsTask>("generatePortalStubs") {
                dispatcherOutputDir.set(stubOutputDir.dir("nested"))
            }
            """.trimIndent(),
        )
        val failed = runner("generatePortalStubs", "--rerun-tasks").buildAndFail()

        assertTrue(failed.output.contains("portal generated output directories must not overlap"), failed.output)
        assertEquals(before, generatedContents())
    }

    @Test
    fun `generated dispatcher and stub decoding reject malformed payloads before service entry`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf("fixture.A", StrictContractService::class.java.name),
            compileGenerated = true,
        )
        projectDir.resolve("src/portalWorker/kotlin/fixture/Main.kt").writeText(
            """
            package fixture
            import io.mazewall.portal.Capability
            import io.mazewall.portal.PortalCodec
            import io.mazewall.portal.PortalMalformedPayloadException
            import io.mazewall.portal.PortalMethod
            import io.mazewall.portal.codegen.testapi.StrictContractService
            import io.mazewall.portal.codegen.testapi.StrictContractServicePortalDispatcher
            import io.mazewall.portal.codegen.testapi.StrictDto
            private fun idFor(name: String): PortalMethod.Generated {
                val method = StrictContractService::class.java.methods.single { it.name == name }
                val key = StrictContractService::class.java.name + "#" + method.name + "#" + method.parameterTypes.joinToString(",") { it.name }
                return PortalMethod.Generated((key.hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000)
            }
            private fun expectMalformed(block: () -> Unit) {
                try {
                    block()
                    error("expected PortalMalformedPayloadException")
                } catch (_: PortalMalformedPayloadException) {
                }
            }
            fun main() {
                var entries = 0
                val service = object : StrictContractService {
                    override fun unitOp() { entries++ }
                    override fun echoInt(value: Int): Int {
                        entries++
                        require(value >= 0) { "service rejected negative value" }
                        return value
                    }
                    override fun echoBool(value: Boolean): Boolean { entries++; return value }
                    override fun echoString(value: String): String { entries++; return value }
                    override fun echoMap(values: Map<String, Int>): Map<String, Int> { entries++; return values }
                    override fun buildDto(label: String): StrictDto { entries++; return StrictDto(label, 7) }
                    override fun acceptDto(value: StrictDto): Int { entries++; return value.count }
                    override fun readGrant(fd: Capability.ReadFd, label: String): Int { entries++; return label.length }
                }

                // 1. Trailing and truncated payloads rejected before service entry.
                expectMalformed {
                    StrictContractServicePortalDispatcher.handle(service, idFor("echoInt"), PortalCodec.encodeInt(1) + byteArrayOf(9), emptyList())
                }
                expectMalformed {
                    StrictContractServicePortalDispatcher.handle(service, idFor("echoInt"), byteArrayOf(0, 1), emptyList())
                }
                expectMalformed {
                    StrictContractServicePortalDispatcher.handle(service, idFor("unitOp"), byteArrayOf(1), emptyList())
                }
                // 2. Noncanonical boolean byte 2, invalid UTF-8 C3 28, and duplicate map keys.
                expectMalformed {
                    StrictContractServicePortalDispatcher.handle(service, idFor("echoBool"), byteArrayOf(2), emptyList())
                }
                expectMalformed {
                    StrictContractServicePortalDispatcher.handle(
                        service,
                        idFor("echoString"),
                        PortalCodec.encodeInt(2) + byteArrayOf(0xC3.toByte(), 0x28),
                        emptyList(),
                    )
                }
                val dupMap = PortalCodec.concat(
                    listOf(
                        PortalCodec.encodeInt(2),
                        PortalCodec.encodeString("k"),
                        PortalCodec.encodeInt(1),
                        PortalCodec.encodeString("k"),
                        PortalCodec.encodeInt(2),
                    ),
                )
                expectMalformed {
                    StrictContractServicePortalDispatcher.handle(service, idFor("echoMap"), dupMap, emptyList())
                }
                // 3. Missing capability rejected before service entry (extra/exact capabilities covered via real grants below).
                expectMalformed {
                    StrictContractServicePortalDispatcher.handle(service, idFor("readGrant"), PortalCodec.encodeString("ok"), emptyList())
                }
                check(entries == 0) { "service entered " + entries + " times on malformed requests" }

                // 4. Positive controls and non-wire IllegalArgumentException distinction.
                check(PortalCodec.Reader(StrictContractServicePortalDispatcher.handle(service, idFor("echoInt"), PortalCodec.encodeInt(5), emptyList())).int() == 5)
                check(PortalCodec.Reader(StrictContractServicePortalDispatcher.handle(service, idFor("echoBool"), PortalCodec.encodeBoolean(true), emptyList())).boolean())
                check(PortalCodec.Reader(StrictContractServicePortalDispatcher.handle(service, idFor("echoString"), PortalCodec.encodeString("ok"), emptyList())).string() == "ok")
                check(StrictContractServicePortalDispatcher.handle(service, idFor("unitOp"), ByteArray(0), emptyList()).isEmpty())
                check(entries == 4)
                val businessFailure = try {
                    StrictContractServicePortalDispatcher.handle(service, idFor("echoInt"), PortalCodec.encodeInt(-1), emptyList())
                    error("expected business IllegalArgumentException")
                } catch (e: IllegalArgumentException) {
                    e
                }
                check(businessFailure.message == "service rejected negative value")
                val dtoFailure = try {
                    StrictContractServicePortalDispatcher.handle(
                        service,
                        idFor("acceptDto"),
                        PortalCodec.concat(listOf(PortalCodec.encodeString("invalid-dto"), PortalCodec.encodeInt(1))),
                        emptyList(),
                    )
                    error("expected DTO IllegalArgumentException")
                } catch (e: IllegalArgumentException) {
                    e
                }
                check(dtoFailure.message == "rejected by dto")
                println("STRICT_DECODE_DISPATCHER_OK")
            }
            """.trimIndent(),
        )

        val result = runner("classes", "roundTrip").build()
        assertBuilt(result, ":compileKotlin")
        assertBuilt(result, ":compilePortalWorkerKotlin")
        assertTrue(result.output.contains("STRICT_DECODE_DISPATCHER_OK"), result.output)

        // Now exercise unmodified compiled StrictContractServicePortalStub against crafted malicious worker replies.
        val generation = PortalWorkerGeneration("a".repeat(64), "b".repeat(64), "c".repeat(64))
        val queuedReplies = ConcurrentLinkedQueue<ByteArray>()
        val peerThreads = mutableListOf<Thread>()
        val dummyBundle = Files.write(projectDir.resolve("dummy-worker.zip"), ByteArray(0))
        val launcher =
            object : ProcessLauncher by RealProcessLauncher {
                override fun startProcess(
                    args: List<String>,
                    redirectErrorStream: Boolean,
                    preparation: JvmLaunchPreparation,
                ): Process {
                    val mainIdx = args.indexOf(PortalWorkerMain::class.java.name)
                    val epPath = args[mainIdx + 1]
                    val child = ProcessBuilder("cat").redirectErrorStream(true).start()
                    val peer =
                        Thread {
                            PortalChannel(RealSocketManager.connect(epPath)).use { channel ->
                                val authFrame = channel.receive(10_000).first
                                val lines =
                                    authFrame.payload
                                        .copyToByteArray()
                                        .toString(Charsets.UTF_8)
                                        .removeSuffix("\n")
                                        .split('\n')
                                val session = lines[1].removePrefix("session=")
                                val launch = lines[3].removePrefix("launch=")
                                val resources = lines[4].removePrefix("resources=")
                                val deadline = Deadline.afterMillis(10_000)
                                channel.sendControl(PortalParentAuthorization(session, child.pid(), launch, resources).acknowledgement(), deadline)
                                channel.sendControl(PortalWorkerReady(session, child.pid(), generation, launch, resources).frame(), deadline)
                                while (!Thread.currentThread().isInterrupted) {
                                    val req = runCatching { channel.receive(10_000).first }.getOrNull() ?: break
                                    val body = queuedReplies.poll() ?: ByteArray(0)
                                    channel.send(PortalFrame(PortalKind.Response, req.requestId, req.method, PortalPayload(body), 0))
                                }
                            }
                        }
                    peer.isDaemon = true
                    peer.start()
                    peerThreads += peer
                    return child
                }
            }
        val broker =
            ProcessBroker(
                launcher = launcher,
                workerClasspath = projectDir.toString(),
                workerLaunch = PortalWorkerLaunch(emptyList(), PortalWorkerArtifact(dummyBundle, "0".repeat(64)), PortalGenerationMeasurement { _, _, _ -> generation }),
                limits = PortalRuntimeLimits(startupTimeoutMs = 5_000, callTimeoutMs = 5_000, closeTimeoutMs = 2_000),
            )
        try {
            broker.start()
            val classesDir = projectDir.resolve("build/classes/kotlin/main").toUri().toURL()
            URLClassLoader(arrayOf(classesDir), javaClass.classLoader).use { loader ->
                val stubClass = loader.loadClass("io.mazewall.portal.codegen.testapi.StrictContractServicePortalStub")
                val stub = stubClass.getConstructor(ProcessBroker::class.java).newInstance(broker) as StrictContractService

                fun awaitReplaced(expectedSpawns: Int) {
                    val wait = Deadline.afterMillis(5_000)
                    while ((broker.spawnedWorkers() < expectedSpawns || broker.idleSize() == 0) && wait.remainingMillis() > 0) {
                        Thread.sleep(10)
                    }
                    assertEquals(expectedSpawns, broker.spawnedWorkers())
                }

                queuedReplies += byteArrayOf(1)
                assertThrows(PortalMalformedPayloadException::class.java) { stub.unitOp() }
                awaitReplaced(2)

                queuedReplies += PortalCodec.encodeInt(1) + byteArrayOf(9)
                assertThrows(PortalMalformedPayloadException::class.java) { stub.echoInt(1) }
                awaitReplaced(3)

                queuedReplies += byteArrayOf(0, 1)
                assertThrows(PortalMalformedPayloadException::class.java) { stub.echoInt(1) }
                awaitReplaced(4)

                queuedReplies += byteArrayOf(2)
                assertThrows(PortalMalformedPayloadException::class.java) { stub.echoBool(true) }
                awaitReplaced(5)

                queuedReplies += PortalCodec.encodeInt(2) + byteArrayOf(0xC3.toByte(), 0x28)
                assertThrows(PortalMalformedPayloadException::class.java) { stub.echoString("ok") }
                awaitReplaced(6)

                queuedReplies +=
                    PortalCodec.concat(
                        listOf(
                            PortalCodec.encodeInt(2),
                            PortalCodec.encodeString("dup"),
                            PortalCodec.encodeInt(1),
                            PortalCodec.encodeString("dup"),
                            PortalCodec.encodeInt(2),
                        ),
                    )
                assertThrows(PortalMalformedPayloadException::class.java) { stub.echoMap(emptyMap()) }
                awaitReplaced(7)

                // Wire validation (including requireExhausted) must defeat host DTO constructor failure
                // and retire the corrupt worker before any DTO failure escapes.
                queuedReplies += PortalCodec.concat(listOf(PortalCodec.encodeString("invalid-dto"), PortalCodec.encodeInt(1))) + byteArrayOf(9)
                assertThrows(PortalMalformedPayloadException::class.java) { stub.buildDto("x") }
                awaitReplaced(8)

                // A valid-wire DTO whose host constructor throws preserves its identity and keeps the healthy worker.
                queuedReplies += PortalCodec.concat(listOf(PortalCodec.encodeString("invalid-dto"), PortalCodec.encodeInt(1)))
                val dtoFailure = assertThrows(IllegalArgumentException::class.java) { stub.buildDto("x") }
                assertEquals("rejected by dto", dtoFailure.message)
                assertEquals(8, broker.spawnedWorkers())

                queuedReplies += PortalCodec.concat(listOf(PortalCodec.encodeString("ok"), PortalCodec.encodeInt(42)))
                assertEquals(StrictDto("ok", 42), stub.buildDto("ok"))
                assertEquals(8, broker.spawnedWorkers())

                // Compiled host stub bound to a stateful singleton broker terminally loses state on malformed wire replies.
                val singletonBroker =
                    ProcessBroker(
                        launcher = launcher,
                        workerClasspath = projectDir.toString(),
                        instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
                        workerLaunch = PortalWorkerLaunch(emptyList(), PortalWorkerArtifact(dummyBundle, "0".repeat(64)), PortalGenerationMeasurement { _, _, _ -> generation }),
                        limits = PortalRuntimeLimits(workers = 1, startupTimeoutMs = 5_000, callTimeoutMs = 5_000, closeTimeoutMs = 2_000),
                    )
                try {
                    singletonBroker.start()
                    val singletonStub = stubClass.getConstructor(ProcessBroker::class.java).newInstance(singletonBroker) as StrictContractService
                    queuedReplies += PortalCodec.concat(listOf(PortalCodec.encodeString("invalid-dto"), PortalCodec.encodeInt(1)))
                    assertEquals("rejected by dto", assertThrows(IllegalArgumentException::class.java) { singletonStub.buildDto("x") }.message)
                    assertEquals(1, singletonBroker.spawnedWorkers())

                    queuedReplies += PortalCodec.concat(listOf(PortalCodec.encodeString("ok"), PortalCodec.encodeInt(42))) + byteArrayOf(9)
                    assertThrows(PortalMalformedPayloadException::class.java) { singletonStub.buildDto("x") }
                    assertTrue(singletonBroker.lifecycleState() is ProcessBrokerLifecycle.Failed)
                    assertEquals(1, singletonBroker.spawnedWorkers())
                    assertThrows(IllegalStateException::class.java) { singletonStub.echoInt(1) }
                } finally {
                    singletonBroker.close()
                }
            }

            // Also verify dispatcher capability-count enforcement with real granted file descriptors.
            val workerClassesDir = projectDir.resolve("build/classes/kotlin/portalWorker").toUri().toURL()
            URLClassLoader(arrayOf(workerClassesDir), javaClass.classLoader).use { workerLoader ->
                val dispatcherClass = workerLoader.loadClass("io.mazewall.portal.codegen.testapi.StrictContractServicePortalDispatcher")
                val instance = dispatcherClass.getField("INSTANCE").get(null)
                val handleMethod =
                    dispatcherClass.getMethod(
                        "handle",
                        StrictContractService::class.java,
                        PortalMethod.Generated::class.java,
                        ByteArray::class.java,
                        List::class.java,
                    )
                var readEntries = 0
                val countingService =
                    object : StrictContractService {
                        override fun unitOp() = Unit

                        override fun echoInt(value: Int) = value

                        override fun echoBool(value: Boolean) = value

                        override fun echoString(value: String) = value

                        override fun echoMap(values: Map<String, Int>) = values

                        override fun buildDto(label: String) = StrictDto(label, 0)

                        override fun acceptDto(value: StrictDto) = value.count

                        override fun readGrant(
                            fd: Capability.ReadFd,
                            label: String,
                        ): Int {
                            readEntries++
                            return label.length
                        }
                    }
                val readGrantMethod = StrictContractService::class.java.methods.single { it.name == "readGrant" }
                val readGrantId = PortalMethod.Generated(PortalStubGenerator.methodId(StrictContractService::class.java, readGrantMethod))
                Files.writeString(projectDir.resolve("grant.txt"), "grant-bytes")
                val grantA = openGrantedRead(projectDir, "grant.txt")
                val grantB = openGrantedRead(projectDir, "grant.txt")
                try {
                    val missingFailure =
                        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                            handleMethod.invoke(instance, countingService, readGrantId, PortalCodec.encodeString("ok"), emptyList<Capability.ReadFd>())
                        }
                    assertTrue(missingFailure.targetException is PortalMalformedPayloadException)
                    val extraFailure =
                        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                            handleMethod.invoke(instance, countingService, readGrantId, PortalCodec.encodeString("ok"), listOf(grantA, grantB))
                        }
                    assertTrue(extraFailure.targetException is PortalMalformedPayloadException)
                    assertEquals(0, readEntries)

                    val validReply =
                        handleMethod.invoke(instance, countingService, readGrantId, PortalCodec.encodeString("hello"), listOf(grantA)) as ByteArray
                    assertEquals(5, PortalCodec.Reader(validReply).int())
                    assertEquals(1, readEntries)
                } finally {
                    grantA.close()
                    grantB.close()
                }
            }
        } finally {
            broker.close()
            peerThreads.forEach { it.interrupt() }
            peerThreads.forEach { it.join(1_000) }
        }
    }

    @Test
    fun `generated request and reply encoding enforce bounds before allocation and traversal`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(
            apiJar,
            listOf("fixture.BoundedEncodeService"),
            compileGenerated = true,
        )
        projectDir.resolve("src/portalWorker/kotlin/fixture/Main.kt").writeText(
            """
            package fixture
            import io.mazewall.portal.PortalCallException
            import io.mazewall.portal.PortalCodec
            import io.mazewall.portal.PortalFrame
            import io.mazewall.portal.PortalInvocationEvidence
            import io.mazewall.portal.PortalMethod
            import io.mazewall.portal.PortalRequestWriter
            private fun idFor(name: String): PortalMethod.Generated {
                val method = BoundedEncodeService::class.java.methods.single { it.name == name }
                val key = BoundedEncodeService::class.java.name + "#" + method.name + "#" + method.parameterTypes.joinToString(",") { it.name }
                return PortalMethod.Generated((key.hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000)
            }
            fun main() {
                var elementReads = 0
                val counting16385 = object : java.util.AbstractList<Int>() {
                    override val size: Int = PortalCodec.MAX_COLLECTION_ELEMENTS + 1
                    override fun get(index: Int): Int {
                        elementReads++
                        return index
                    }
                }
                val counting4Strings = object : java.util.AbstractList<String>() {
                    override val size: Int = 4
                    override fun get(index: Int): String {
                        elementReads++
                        return "x".repeat(20)
                    }
                }
                val service = object : BoundedEncodeService {
                    override fun listInts(values: List<Int>): List<Int> =
                        if (values.size == 1) counting16385 else List(PortalCodec.MAX_COLLECTION_ELEMENTS) { 1 }
                    override fun echoString(value: String): String = value
                    override fun echoStrings(values: List<String>): List<String> = counting4Strings
                    override fun sendLists(values: List<String>): Int = values.size
                    override fun sendThrowingPojo(value: ThrowingGetterPojo): Int = 0
                }

                // Exact 16,384-element reply succeeds; 16,385 fails before reading any elements.
                val exactReply = BoundedEncodeServicePortalDispatcher.handle(
                    service,
                    idFor("listInts"),
                    PortalCodec.encodeInt(0),
                    emptyList(),
                )
                check(PortalCodec.Reader(exactReply).collectionCount() == PortalCodec.MAX_COLLECTION_ELEMENTS)
                val overCount = try {
                    BoundedEncodeServicePortalDispatcher.handle(
                        service,
                        idFor("listInts"),
                        PortalCodec.concat(listOf(PortalCodec.encodeInt(1), PortalCodec.encodeInt(0))),
                        emptyList(),
                    )
                    error("expected 16,385 reply rejection")
                } catch (e: PortalCallException) {
                    e
                }
                check(overCount.evidence == PortalInvocationEvidence.NOT_STARTED)
                check(elementReads == 0) { "expected 0 element reads before rejection, saw " + elementReads }

                // Reply exceeding 8 MiB rejects before creating an over-limit encoded array.
                val giant = "a".repeat(PortalFrame.MAX_PAYLOAD - 3)
                val hugeReply = try {
                    BoundedEncodeServicePortalDispatcher.handle(
                        service,
                        idFor("echoString"),
                        PortalCodec.encodeString(giant),
                        emptyList(),
                    )
                    error("expected >8 MiB string reply rejection")
                } catch (e: PortalCallException) {
                    e
                }
                check(hugeReply.evidence == PortalInvocationEvidence.NOT_STARTED)

                // Small reply byte limit rejects >64B string and stops counting List<String> mid-traversal before element 3.
                PortalRequestWriter.withReplyLimit(64) {
                    val strAborted = try {
                        BoundedEncodeServicePortalDispatcher.handle(
                            service,
                            idFor("echoString"),
                            PortalCodec.encodeString("x".repeat(61)),
                            emptyList(),
                        )
                        error("expected 64-byte string reply limit failure")
                    } catch (e: PortalCallException) {
                        e
                    }
                    check(strAborted.evidence == PortalInvocationEvidence.NOT_STARTED)

                    elementReads = 0
                    val listAborted = try {
                        BoundedEncodeServicePortalDispatcher.handle(
                            service,
                            idFor("echoStrings"),
                            PortalCodec.encodeInt(0),
                            emptyList(),
                        )
                        error("expected 64-byte list reply limit failure")
                    } catch (e: PortalCallException) {
                        e
                    }
                    check(listAborted.evidence == PortalInvocationEvidence.NOT_STARTED)
                    check(elementReads == 3) { "expected stop at element 2 (3 reads), saw " + elementReads }

                    val exact = BoundedEncodeServicePortalDispatcher.handle(
                        service,
                        idFor("echoString"),
                        PortalCodec.encodeString("x".repeat(60)),
                        emptyList(),
                    )
                    check(PortalCodec.Reader(exact).string().length == 60)
                }
                println("BOUNDED_DISPATCHER_OK")
            }
            """.trimIndent(),
        )

        val result = runner("classes", "roundTrip").build()
        assertBuilt(result, ":compileKotlin")
        assertBuilt(result, ":compilePortalWorkerKotlin")
        assertTrue(result.output.contains("BOUNDED_DISPATCHER_OK"), result.output)

        // Now verify compiled host stub and compiled dispatcher across a live ProcessBroker socket pair.
        val generation = PortalWorkerGeneration("a".repeat(64), "b".repeat(64), "c".repeat(64))
        var dispatchedRequests = 0
        val peerThreads = mutableListOf<Thread>()
        val dummyBundle = Files.write(projectDir.resolve("dummy-bounded-worker.zip"), ByteArray(0))
        val launcher =
            object : ProcessLauncher by RealProcessLauncher {
                override fun startProcess(
                    args: List<String>,
                    redirectErrorStream: Boolean,
                    preparation: JvmLaunchPreparation,
                ): Process {
                    val mainIdx = args.indexOf(PortalWorkerMain::class.java.name)
                    val epPath = args[mainIdx + 1]
                    val child = ProcessBuilder("cat").redirectErrorStream(true).start()
                    val peer =
                        Thread {
                            PortalChannel(RealSocketManager.connect(epPath)).use { channel ->
                                val authFrame = channel.receive(10_000).first
                                val lines =
                                    authFrame.payload
                                        .copyToByteArray()
                                        .toString(Charsets.UTF_8)
                                        .removeSuffix("\n")
                                        .split('\n')
                                val session = lines[1].removePrefix("session=")
                                val launch = lines[3].removePrefix("launch=")
                                val resources = lines[4].removePrefix("resources=")
                                val deadline = Deadline.afterMillis(10_000)
                                channel.sendControl(PortalParentAuthorization(session, child.pid(), launch, resources).acknowledgement(), deadline)
                                channel.sendControl(PortalWorkerReady(session, child.pid(), generation, launch, resources).frame(), deadline)
                                while (!Thread.currentThread().isInterrupted) {
                                    val req = runCatching { channel.receive(10_000).first }.getOrNull() ?: break
                                    dispatchedRequests++
                                    channel.send(PortalFrame(PortalKind.Response, req.requestId, req.method, PortalPayload(req.payload.copyToByteArray()), 0))
                                }
                            }
                        }
                    peer.isDaemon = true
                    peer.start()
                    peerThreads += peer
                    return child
                }
            }

        val broker =
            ProcessBroker(
                launcher = launcher,
                workerClasspath = projectDir.toString(),
                workerLaunch = PortalWorkerLaunch(emptyList(), PortalWorkerArtifact(dummyBundle, "0".repeat(64)), PortalGenerationMeasurement { _, _, _ -> generation }),
                limits = PortalRuntimeLimits(maxRequestBytes = 32, startupTimeoutMs = 5_000, callTimeoutMs = 5_000, closeTimeoutMs = 2_000),
            )
        try {
            broker.start()
            val classesDir = projectDir.resolve("build/classes/kotlin/main").toUri().toURL()
            val apiClassLoader = URLClassLoader(arrayOf(apiJar.toUri().toURL()), javaClass.classLoader)
            URLClassLoader(arrayOf(classesDir), apiClassLoader).use { loader ->
                val serviceInterface = loader.loadClass("fixture.BoundedEncodeService")
                val pojoClass = loader.loadClass("fixture.ThrowingGetterPojo")
                val stubClass = loader.loadClass("fixture.BoundedEncodeServicePortalStub")
                val stub = stubClass.getConstructor(ProcessBroker::class.java).newInstance(broker)

                val sendListsMethod = serviceInterface.getMethod("sendLists", List::class.java)
                val echoStringMethod = serviceInterface.getMethod("echoString", String::class.java)
                val sendPojoMethod = serviceInterface.getMethod("sendThrowingPojo", pojoClass)

                // 16,385 and 40-element lists reject before iterating or dispatching.
                var elementReads = 0
                val overCount = object : java.util.AbstractList<String>() {
                    override val size: Int = 40

                    override fun get(index: Int): String {
                        elementReads++
                        return "a"
                    }
                }
                val countEx = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                    sendListsMethod.invoke(stub, overCount)
                }
                val callFailure = countEx.targetException as PortalCallException
                assertEquals(PortalInvocationEvidence.NOT_STARTED, callFailure.evidence)
                assertEquals(0, elementReads)
                assertEquals(0, dispatchedRequests)

                // Multi-element list stops mid-traversal once an item pushes over the 32-byte limit.
                val midList = object : java.util.AbstractList<String>() {
                    override val size: Int = 4

                    override fun get(index: Int): String {
                        elementReads++
                        return if (index == 2) "x".repeat(30) else "a"
                    }
                }
                val midEx = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                    sendListsMethod.invoke(stub, midList)
                }
                assertEquals(PortalInvocationEvidence.NOT_STARTED, (midEx.targetException as PortalCallException).evidence)
                assertEquals(3, elementReads, "element 3 must never be visited after element 2 exceeds budget")
                assertEquals(0, dispatchedRequests)

                // Oversized string rejects before dispatch; exact 32-byte string dispatches and returns.
                val strEx = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                    echoStringMethod.invoke(stub, "a".repeat(29))
                }
                assertEquals(PortalInvocationEvidence.NOT_STARTED, (strEx.targetException as PortalCallException).evidence)
                assertEquals(0, dispatchedRequests)

                assertEquals("a".repeat(28), echoStringMethod.invoke(stub, "a".repeat(28)))
                assertEquals(1, dispatchedRequests)

                // Throwing POJO getter propagates exact IllegalStateException without dispatching.
                val throwingPojo = pojoClass.getConstructor(String::class.java, Boolean::class.javaPrimitiveType).newInstance("ok", true)
                val getterEx = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                    sendPojoMethod.invoke(stub, throwingPojo)
                }
                assertTrue(getterEx.targetException is IllegalStateException)
                assertEquals("user getter exploded", getterEx.targetException.message)
                assertEquals(1, dispatchedRequests)
            }
        } finally {
            broker.close()
            peerThreads.forEach { it.interrupt() }
            peerThreads.forEach { it.join(1_000) }
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
    fun `invalid DTO shapes fail generation with field path and preserve sentinels`() {
        val apiJar = compileApiJar(projectDir)
        writeFixture(apiJar, listOf("fixture.A", "fixture.B"))
        runner("generatePortalStubs").build()
        val before = generatedContents()

        writeFixture(apiJar, listOf("fixture.A", io.mazewall.portal.codegen.testapi.AbstractDtoService::class.java.name))
        val abstractResult = runner("generatePortalStubs", "--rerun-tasks").buildAndFail()
        assertTrue(abstractResult.output.contains("abstract DTO io.mazewall.portal.codegen.testapi.AbstractDto is not constructible"), abstractResult.output)
        assertTrue(abstractResult.output.contains("AbstractDtoService.echo parameter dto"), abstractResult.output)
        assertEquals(before, generatedContents())

        writeFixture(apiJar, listOf("fixture.A", io.mazewall.portal.codegen.testapi.NullableMemberDtoService::class.java.name))
        val nullableResult = runner("generatePortalStubs", "--rerun-tasks").buildAndFail()
        assertTrue(nullableResult.output.contains("nullable DTO components are not supported"), nullableResult.output)
        assertTrue(nullableResult.output.contains("NullableMemberDtoService.echo parameter dto field name"), nullableResult.output)
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
        assertBuilt(result, ":generatePortalStubs")

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

        val hostOnly = projectDir.resolve("host-only.jar")
        java.util.jar
            .JarOutputStream(
            java.nio.file.Files
            .newOutputStream(hostOnly),
        ).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("host-only-marker.txt"))
            jar.write("must not reach worker".toByteArray())
            jar.closeEntry()
        }
        projectDir.resolve("build.gradle.kts").toFile().appendText("\ndependencies { implementation(files(\"$hostOnly\")) }\n")
        val result = runner("jar", "publishPortalWorkerArtifactMetadata").build()

        assertBuilt(result, ":publishPortalWorkerArtifactMetadata")
        val metadata =
            projectDir.resolve(
                "build/generated/portal-worker-artifacts/META-INF/mazewall/portal/worker-artifacts/fixture-worker",
            )
        assertTrue(metadata.readText().contains("sha256="), metadata.readText())
        assertTrue(metadata.readText().startsWith("format=2\n"), metadata.readText())
        assertFalse(metadata.readText().contains(projectDir.toString()), metadata.readText())
        java.util.zip.ZipFile(projectDir.resolve("build/libs/portal-codegen-fixture-portal-worker.zip").toFile()).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.endsWith("host-only.jar") }, "host implementation dependencies must not be inherited by worker bundles")
        }
        assertEquals(
            "fixture-worker\n",
            projectDir
                .resolve(
                "build/generated/portal-worker-artifacts/META-INF/mazewall/portal/worker-artifacts.index",
            ).readText(),
        )
        val relocated = projectDir.resolve("relocated").createDirectories()
        java.nio.file.Files
            .copy(projectDir.resolve("build/libs/portal-codegen-fixture.jar"), relocated.resolve("host.jar"))
        projectDir.resolve("build").toFile().deleteRecursively()
        java.net.URLClassLoader(arrayOf(relocated.resolve("host.jar").toUri().toURL()), null).use { loader ->
            val artifact = io.mazewall.portal.PortalWorkerArtifactCatalog
                .load(loader, relocated.resolve("cache"))
                .getValue("fixture-worker")
            val bundle = io.mazewall.portal.WorkerBundleMaterializer
                .materialize(artifact, relocated.resolve("cache"))
            assertTrue(bundle.classpath.all(java.nio.file.Files::isRegularFile))
            java.util.jar.JarFile(relocated.resolve("host.jar").toFile()).use { jar ->
                assertFalse(jar.entries().asSequence().any { it.name.contains("PortalDispatcher.class") }, "guest dispatchers must remain opaque bundle data")
            }
        }
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
        val apiPath = projectDir.relativize(apiJar)
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
                ${if (applyKotlinPlugin) "kotlin(\"jvm\") version \"2.4.21\"" else "java"}
                id("io.mazewall.portal.codegen")
            }
            repositories { mavenCentral() }
            ${if (workerSourceSet != null && workerSourceSet !in setOf("main", "test")) "sourceSets.create(\"$workerSourceSet\")" else ""}
            dependencies {
                implementation(files(
                    "$apiPath", "$portalJar", "$platformJar", "$enforcerJar",
                    "$testApiClasses", "$javaTestApiClasses",
                ))
                ${if (workerSourceSet != null &&
                workerSourceSet !in setOf(
                    "main",
                    "test",
                )
            ) {
                    "\"${workerSourceSet}Implementation\"(files(\"$apiPath\", \"$portalJar\", \"$platformJar\", \"$enforcerJar\", \"$testApiClasses\", \"$javaTestApiClasses\"))"
                } else {
                    ""
                }}
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
                ${if (services.contains(io.mazewall.portal.codegen.testapi.NamesRecordService::class.java.name)) {
                """
                val namesImplementation = object : io.mazewall.portal.codegen.testapi.NamesRecordService {
                    override fun echo(names: io.mazewall.portal.codegen.testapi.NamesRecord) =
                        io.mazewall.portal.codegen.testapi.NamesRecord(names.values().map { it + "!" })
                }
                val namesPayload = PortalCodec.concat(
                    listOf(
                        PortalCodec.encodeInt(2),
                        PortalCodec.encodeString("hello"),
                        PortalCodec.encodeString("world"),
                    ),
                )
                val namesResult = io.mazewall.portal.codegen.testapi.NamesRecordServicePortalDispatcher.handle(
                    namesImplementation,
                    PortalMethod.Generated(
                        (("io.mazewall.portal.codegen.testapi.NamesRecordService#echo#" +
                            "io.mazewall.portal.codegen.testapi.NamesRecord").hashCode() and Int.MAX_VALUE) % 1_000_000_000 + 1_000,
                    ),
                    namesPayload,
                    emptyList(),
                )
                val namesReader = PortalCodec.Reader(namesResult)
                check(namesReader.int() == 2)
                check(namesReader.string() == "hello!")
                check(namesReader.string() == "world!")
                """
            } else {
                ""
            }}
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
        sources.resolve("BooleanPojo.java").writeText(
            """
            package fixture;
            public final class BooleanPojo {
                private final boolean primitiveEnabled;
                private final Boolean boxedEnabled;
                public BooleanPojo(boolean primitiveEnabled, Boolean boxedEnabled) {
                    this.primitiveEnabled = primitiveEnabled;
                    this.boxedEnabled = boxedEnabled;
                }
                public boolean isPrimitiveEnabled() { return primitiveEnabled; }
                public Boolean isBoxedEnabled() { return boxedEnabled; }
            }
            """.trimIndent(),
        )
        sources.resolve("BooleanPojoService.java").writeText(
            "package fixture; public interface BooleanPojoService { BooleanPojo invert(BooleanPojo value); }",
        )
        sources.resolve("ArrayAndListService.java").writeText(
            "package fixture; public interface ArrayAndListService { byte[] echo(byte[] value); java.util.List<Integer> many(); }",
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
        sources.resolve("ThrowingGetterPojo.java").writeText(
            """
            package fixture;
            public final class ThrowingGetterPojo {
                private final String name;
                private final boolean failOnRead;
                public ThrowingGetterPojo(String name, boolean failOnRead) {
                    this.name = name;
                    this.failOnRead = failOnRead;
                }
                public String getName() {
                    if (failOnRead) throw new IllegalStateException("user getter exploded");
                    return name;
                }
                public boolean isFailOnRead() { return failOnRead; }
            }
            """.trimIndent(),
        )
        sources.resolve("BoundedEncodeService.java").writeText(
            """
            package fixture;
            public interface BoundedEncodeService {
                java.util.List<Integer> listInts(java.util.List<Integer> values);
                String echoString(String value);
                java.util.List<String> echoStrings(java.util.List<String> values);
                int sendLists(java.util.List<String> values);
                int sendThrowingPojo(ThrowingGetterPojo value);
            }
            """.trimIndent(),
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
                sources.resolve("BooleanPojo.java").toString(),
                sources.resolve("BooleanPojoService.java").toString(),
                sources.resolve("ArrayAndListService.java").toString(),
                sources.resolve("NestedDtos.java").toString(),
                sources.resolve("CollisionOuter.java").toString(),
                sources.resolve("Nested_14_CollisionOuter_7_Service.java").toString(),
                sources.resolve("ThrowingGetterPojo.java").toString(),
                sources.resolve("BoundedEncodeService.java").toString(),
            )
        assertEquals(0, exit)
        val jar = root.resolve("api.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    val entry = classes.relativize(file).toString().replace('\\', '/')
                    output.putNextEntry(JarEntry(entry).apply { time = 0 })
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

    private fun assertBuilt(
        result: BuildResult,
        task: String,
    ) {
        assertTrue(result.task(task)?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE), result.output)
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(*arrayOf("--stacktrace", "--build-cache", "--max-workers=1", *arguments))
}
