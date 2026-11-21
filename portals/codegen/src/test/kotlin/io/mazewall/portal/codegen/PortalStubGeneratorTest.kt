package io.mazewall.portal.codegen

import io.mazewall.portal.PortalGeneratedNames
import io.mazewall.portal.codegen.testapi.BadStream
import io.mazewall.portal.codegen.testapi.BinaryAndCollectionService
import io.mazewall.portal.codegen.testapi.CompanionShadowService
import io.mazewall.portal.codegen.testapi.FirstOuter
import io.mazewall.portal.codegen.testapi.InheritedService
import io.mazewall.portal.codegen.testapi.KotlinDataDtoService
import io.mazewall.portal.codegen.testapi.KotlinJvmRecordDtoService
import io.mazewall.portal.codegen.testapi.MethodIdShadowService
import io.mazewall.portal.codegen.testapi.NullableService
import io.mazewall.portal.codegen.testapi.Overloads
import io.mazewall.portal.codegen.testapi.SampleFd
import io.mazewall.portal.codegen.testapi.SampleGeom
import io.mazewall.portal.codegen.testapi.SampleGreeter
import io.mazewall.portal.codegen.testapi.SecondOuter
import io.mazewall.portal.codegen.testapi.SnapshotStream
import io.mazewall.portal.codegen.testapi.StrictContractService
import io.mazewall.portal.codegen.testapi.UnreadablePojoService
import io.mazewall.portal.codegen.testapi.ValidPojoService
import io.mazewall.portal.codegen.testapi.WrongReaderPojoService
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class PortalStubGeneratorTest {
    @Test
    fun `nested services receive distinct loadable generated names`() {
        val first = PortalStubGenerator.generate(FirstOuter.NestedService::class.java)
        val second = PortalStubGenerator.generate(SecondOuter.NestedService::class.java)

        assertTrue(first.any { it.name == PortalGeneratedNames.stubBinaryName(FirstOuter.NestedService::class.java).substringAfterLast('.') }, first.toString())
        assertTrue(first.any { it.name == PortalGeneratedNames.dispatcherBinaryName(FirstOuter.NestedService::class.java).substringAfterLast('.') }, first.toString())
        assertTrue(second.any { it.name == PortalGeneratedNames.stubBinaryName(SecondOuter.NestedService::class.java).substringAfterLast('.') }, second.toString())
        assertTrue(second.any { it.name == PortalGeneratedNames.dispatcherBinaryName(SecondOuter.NestedService::class.java).substringAfterLast('.') }, second.toString())
    }

    @Test
    fun `Companion parameter cannot shadow generated method id qualifier`() {
        val stub =
            PortalStubGenerator
                .generate(CompanionShadowService::class.java)
                .single { it.name.endsWith("PortalStub") }
                .toString()

        assertTrue(stub.contains("Companion: String"), stub)
        assertTrue(stub.contains("CompanionShadowServicePortalStub.Companion."), stub)
    }

    @Test
    fun `nullable Kotlin signatures remain nullable in generated overrides`() {
        val files = PortalStubGenerator.generate(NullableService::class.java)
        val stub = files.single { it.name.endsWith("PortalStub") }.toString()
        val dispatcher = files.single { it.name.endsWith("PortalDispatcher") }.toString()

        assertTrue(Regex("""\w+: String\?""").containsMatchIn(stub), stub)
        assertTrue(Regex("""fun echo\([^:]+: String\?\): String\?""").containsMatchIn(stub), stub)
        assertTrue(Regex("""val \w+: String\?""").containsMatchIn(dispatcher), dispatcher)
    }

    @Test
    fun `capability placement reports service method and nested property`() {
        listOf(
            io.mazewall.portal.codegen.testapi.ReturnedFdService::class.java to "acquire.return",
            io.mazewall.portal.codegen.testapi.NestedFdService::class.java to "consume.value.fd",
        ).forEach { (service, path) ->
            val failure = assertThrows(IllegalArgumentException::class.java) { PortalStubGenerator.generate(service) }
            assertTrue(failure.message!!.contains(service.name), failure.message)
            assertTrue(failure.message!!.contains(path), failure.message)
        }
    }

    @Test
    fun `computed and delegated constructor components are rejected`() {
        listOf(
            io.mazewall.portal.codegen.testapi.ComputedDtoService::class.java,
            io.mazewall.portal.codegen.testapi.DelegatedDtoService::class.java,
        ).forEach { service ->
            val failure = assertThrows(IllegalArgumentException::class.java) { PortalStubGenerator.generate(service) }
            assertTrue(failure.message!!.contains("constructor-backed"), failure.message)
        }
    }

    @Test
    fun `host stub serializes strings and never mentions Impl`() {
        val files = PortalStubGenerator.generate(SampleGreeter::class.java)
        val stub = files.single { it.name == "SampleGreeterPortalStub" }.toString()
        val dispatcher = files.single { it.name == "SampleGreeterPortalDispatcher" }.toString()
        assertTrue(stub.contains("SampleGreeterPortalStub"))
        assertTrue(stub.contains("ProcessBroker"))
        assertTrue(stub.contains("broker.invoke"))
        assertTrue(stub.contains("PortalMethod.Generated"))
        assertTrue(stub.contains("PortalCodec.encodeString"))
        assertTrue(stub.contains("GREET_METHOD_ID"))
        assertFalse(stub.contains("Impl("))
        assertTrue(dispatcher.contains("SampleGreeterPortalDispatcher"))
        assertTrue(dispatcher.contains(".greet"))
        assertTrue(dispatcher.contains("method: PortalMethod.Generated"))
        assertTrue(dispatcher.contains("GREET_METHOD_ID"))
        assertFalse(dispatcher.contains("Impl("))
    }

    @Test
    fun `dispatcher exposes METHOD_IDS for worker registry bootstrap`() {
        val files = PortalStubGenerator.generate(SampleGreeter::class.java)
        val dispatcher = files.single { it.name == "SampleGreeterPortalDispatcher" }.toString()
        assertTrue(dispatcher.contains("val METHOD_IDS: IntArray"), "registry bootstrap needs METHOD_IDS")
        // Ids must stay outside the builtin range (PortalMethods 1..4).
        val ids = Regex("const val [A-Z_]+_METHOD_ID: Int = ([\\d_]+)")
            .find(dispatcher)!!
            .groupValues[1]
            .let { listOf(it.replace("_", "").toInt()) }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { it >= 1000 }, "generated ids must not collide with builtins: $ids")
    }

    @Test
    fun `existing wire method ids remain stable`() {
        assertTrue(
            PortalStubGenerator.methodId(
                SampleGreeter::class.java,
                SampleGreeter::class.java.getDeclaredMethod("greet", String::class.java),
            ) == 367_740_916,
        )
        assertTrue(
            PortalStubGenerator.methodId(
                SampleGreeter::class.java,
                SampleGreeter::class.java.getDeclaredMethod("add", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            ) == 575_993_707,
        )
    }

    @Test
    fun `record components are allowed`() {
        val files = PortalStubGenerator.generate(SampleGeom::class.java)
        val stub = files.single { it.name == "SampleGeomPortalStub" }.toString()
        assertTrue(stub.contains("p.x") || stub.contains("encodeInt"))
        assertTrue(stub.contains("PortalCodec.concat"))
    }

    @Test
    fun `ReadFd is attached not serialized`() {
        val files = PortalStubGenerator.generate(SampleFd::class.java)
        val stub = files.single { it.name == "SampleFdPortalStub" }.toString()
        val dispatcher = files.single { it.name == "SampleFdPortalDispatcher" }.toString()
        assertTrue(stub.contains("broker.invoke"))
        assertFalse(stub.contains("encodeString(fd)"))
        assertTrue(dispatcher.contains("Capability slot 0: fd"), dispatcher)
    }

    @Test
    fun `InputStream is rejected at generate time`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                PortalStubGenerator.generate(BadStream::class.java)
            }
        assertTrue(ex.message!!.contains("java.io.InputStream"))
    }

    @Test
    fun `InputStream is accepted only with an explicit bounded snapshot adapter`() {
        val files = PortalStubGenerator.generate(SnapshotStream::class.java)
        val stub = files.single { it.name == "SnapshotStreamPortalStub" }.toString()
        val dispatcher = files.single { it.name == "SnapshotStreamPortalDispatcher" }.toString()

        assertTrue(stub.contains("PortalInputSnapshot.capture(source, 64)"), stub)
        assertTrue(stub.contains("encodeBytes(it.bytes())"), stub)
        assertTrue(dispatcher.contains("reader.bytes().inputStream()"), dispatcher)
    }

    @Test
    fun `common immutable scalar values are generated as bounded string values`() {
        val files = PortalStubGenerator.generate(CommonScalarService::class.java)
        val stub = files.single { it.name == "CommonScalarServicePortalStub" }.toString()
        val dispatcher = files.single { it.name == "CommonScalarServicePortalDispatcher" }.toString()

        assertTrue(stub.contains("reader.uuid()"), stub)
        assertTrue(stub.contains("reader.instant()"), stub)
        assertTrue(stub.contains("reader.bigDecimal()"), stub)
        assertTrue(dispatcher.contains("toString()"), dispatcher)
    }

    @Test
    fun `enums are generated by stable constant name rather than ordinal`() {
        val files = PortalStubGenerator.generate(EnumService::class.java)
        val stub = files.single { it.name == "EnumServicePortalStub" }.toString()
        val dispatcher = files.single { it.name == "EnumServicePortalDispatcher" }.toString()

        assertTrue(stub.contains("enumValue(TransportKind::class.java)"), stub)
        assertTrue(dispatcher.contains(".name"), dispatcher)
        assertFalse(stub.contains("ordinal"), stub)
        assertFalse(dispatcher.contains("ordinal"), dispatcher)
    }

    @Test
    fun `declared exceptions are the only remote exceptions mapped by a host stub`() {
        val stub =
            PortalStubGenerator
                .generate(DeclaredExceptionService::class.java)
                .single { it.name == "DeclaredExceptionServicePortalStub" }
                .toString()

        assertTrue(stub.contains("catch (remote: PortalRemoteCallException)"), stub)
        assertTrue(stub.contains("PortalDeclaredExceptionMapper.rethrow(remote, arrayOf(InvalidDocument::class.java))"), stub)
    }

    @Test
    fun `overloads have distinct generated wire identities`() {
        val files = PortalStubGenerator.generate(Overloads::class.java)
        val stub = files.single { it.name == "OverloadsPortalStub" }.toString()
        val dispatcher = files.single { it.name == "OverloadsPortalDispatcher" }.toString()

        assertTrue(stub.contains("override fun echo(s: String)"), stub)
        assertTrue(stub.contains("override fun echo(n: Int)"), stub)
        assertTrue(dispatcher.contains("ECHO_METHOD_ID"), dispatcher)
        assertTrue(dispatcher.contains("ECHO_METHOD_ID_"), dispatcher)
    }

    @Test
    fun `effective inherited methods and override are generated once`() {
        val files = PortalStubGenerator.generate(InheritedService::class.java)
        val stub = files.single { it.name == "InheritedServicePortalStub" }.toString()
        val dispatcher = files.single { it.name == "InheritedServicePortalDispatcher" }.toString()

        assertTrue(stub.contains("override fun inherited"), stub)
        assertTrue(stub.contains("override fun overridden"), stub)
        assertTrue(dispatcher.split(".overridden(").size == 2, dispatcher)
        assertTrue(dispatcher.contains("PING_METHOD_ID"), dispatcher)
        assertTrue(dispatcher.contains("PING_METHOD_ID_"), dispatcher)
    }

    @Test
    fun `compiled schema collections reject mutation`() {
        val schema = PortalServiceSchema.compile(InheritedService::class.java)

        @Suppress("UNCHECKED_CAST")
        val methods = schema.methods as MutableList<PortalMethodSchema>
        assertThrows(UnsupportedOperationException::class.java) { methods.clear() }
        @Suppress("UNCHECKED_CAST")
        val parameters = schema.methods.first { it.method.name == "payload" }.parameters as MutableList<PortalParameterSchema>
        assertThrows(UnsupportedOperationException::class.java) { parameters.clear() }
    }

    @Test
    fun `hostile parameter names do not collide with generated locals`() {
        val stub =
            PortalStubGenerator
                .generate(InheritedService::class.java)
                .single { it.name == "InheritedServicePortalStub" }
                .toString()
        assertTrue(stub.contains("val payload_"), stub)
        assertTrue(stub.contains("val reader_"), stub)
        assertTrue(stub.contains("val result_"), stub)
    }

    @Test
    fun `pojo readers are validated and emitted explicitly`() {
        val stub =
            PortalStubGenerator
                .generate(ValidPojoService::class.java)
                .single { it.name == "ValidPojoServicePortalStub" }
                .toString()
        assertTrue(stub.contains(".getName()"), stub)
        assertTrue(stub.contains(".count()"), stub)
    }

    @Test
    fun `Kotlin data class readers are modeled and emitted as property access`() {
        val method = PortalServiceSchema.compile(KotlinDataDtoService::class.java).methods.single()
        val kind = method.parameters.single().kind as BoundaryTypes.Kind.RecordT
        assertTrue(kind.components.all { it.reader is BoundaryTypes.Kind.Reader.KotlinProperty }, kind.toString())

        val stub =
            PortalStubGenerator
                .generate(KotlinDataDtoService::class.java)
                .single { it.name == "KotlinDataDtoServicePortalStub" }
                .toString()
        assertTrue(stub.contains(".name)"), stub)
        assertTrue(stub.contains(".count)"), stub)
        assertFalse(stub.contains(".getName()"), stub)
        assertFalse(stub.contains(".getCount()"), stub)
    }

    @Test
    fun `Kotlin JVM records use record component accessors`() {
        val method = PortalServiceSchema.compile(KotlinJvmRecordDtoService::class.java).methods.single()
        val kind = method.parameters.single().kind as BoundaryTypes.Kind.RecordT
        assertTrue(kind.components.all { it.reader is BoundaryTypes.Kind.Reader.KotlinProperty }, kind.toString())

        val stub =
            PortalStubGenerator
                .generate(KotlinJvmRecordDtoService::class.java)
                .single { it.name == "KotlinJvmRecordDtoServicePortalStub" }
                .toString()
        assertTrue(stub.contains(".name)"), stub)
        assertTrue(stub.contains(".count)"), stub)
    }

    @Test
    fun `method id constant cannot be shadowed by its identically named parameter`() {
        val method = MethodIdShadowService::class.java.getDeclaredMethod("pinned", String::class.java)
        assertTrue(PortalStubGenerator.methodId(MethodIdShadowService::class.java, method) == 654_749_016)

        val stub =
            PortalStubGenerator
                .generate(MethodIdShadowService::class.java)
                .single { it.name == "MethodIdShadowServicePortalStub" }
                .toString()
        assertTrue(stub.contains("pinned(PINNED_METHOD_ID: String)"), stub)
        assertTrue(stub.contains("PortalMethod.Generated(MethodIdShadowServicePortalStub.Companion.PINNED_METHOD_ID)"), stub)
    }

    @Test
    fun `pojo without readable constructor component is rejected`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                PortalStubGenerator.generate(UnreadablePojoService::class.java)
            }
        assertTrue(ex.message!!.contains("readable accessor"), ex.message)
        assertTrue(ex.message!!.contains("hidden"), ex.message)
    }

    @Test
    fun `pojo reader with incompatible type is rejected`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                PortalStubGenerator.generate(WrongReaderPojoService::class.java)
            }
        assertTrue(ex.message!!.contains("type-compatible"), ex.message)
        assertTrue(ex.message!!.contains("value"), ex.message)
    }

    @Test
    fun `write splits stub and dispatcher into different directories`() {
        val stubDir = kotlin.io.path
            .createTempDirectory("portal-stubs")
            .toFile()
        val dispatcherDir = kotlin.io.path
            .createTempDirectory("portal-dispatchers")
            .toFile()
        PortalStubGenerator.write(SampleGreeter::class.java, stubDir, dispatcherDir)
        val stubFiles = stubDir
            .walkTopDown()
            .filter { it.isFile }
            .map { it.name }
            .toList()
        val dispatcherFiles = dispatcherDir
            .walkTopDown()
            .filter { it.isFile }
            .map { it.name }
            .toList()
        assertTrue(stubFiles.any { it.contains("PortalStub") }, stubFiles.toString())
        assertTrue(dispatcherFiles.any { it.contains("PortalDispatcher") }, dispatcherFiles.toString())
        assertTrue(stubFiles.none { it.contains("PortalDispatcher") }, stubFiles.toString())
        assertTrue(dispatcherFiles.none { it.contains("PortalStub") }, dispatcherFiles.toString())
        val dispatcherSource = dispatcherDir.walkTopDown().first { it.isFile && it.name.contains("PortalDispatcher") }.readText()
        assertTrue(dispatcherSource.contains("GREET_METHOD_ID"), dispatcherSource)
        assertTrue(dispatcherSource.contains("when (method.wire)"), dispatcherSource)
    }

    @Test
    fun `plugin apply registers portal generation and diagnostic tasks`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(PortalCodegenPlugin::class.java)
        assertTrue(project.tasks.findByName("generatePortalStubs") != null)
        assertTrue(project.tasks.findByName("portalCheck") != null)
        assertTrue(project.tasks.findByName("portalDescribe") != null)
    }

    @Test
    fun `byte array and list contracts emit compilable ByteArray List and Map signatures`() {
        val files = PortalStubGenerator.generate(BinaryAndCollectionService::class.java)
        val stub = files.single { it.name == "BinaryAndCollectionServicePortalStub" }.toString()
        val dispatcher = files.single { it.name == "BinaryAndCollectionServicePortalDispatcher" }.toString()

        assertTrue(stub.contains("override fun echoBytes(value_: ByteArray): ByteArray"), stub)
        assertTrue(stub.contains("override fun echoReadOnly(values: List<ByteArray>): List<ByteArray>"), stub)
        assertTrue(stub.contains("override fun echoMutable(values: MutableList<ByteArray>): MutableList<ByteArray>"), stub)
        assertTrue(
            stub.contains("override fun echoNested(values: Map<String, List<ByteArray>>): MutableMap<String, MutableList<ByteArray>>"),
            stub,
        )
        assertFalse(stub.contains("Array<Byte>"), stub)
        assertFalse(dispatcher.contains("Array<Byte>"), dispatcher)
    }

    @Test
    fun `generated stubs and dispatchers emit strict capability context and exhaustion checks`() {
        val files = PortalStubGenerator.generate(StrictContractService::class.java)
        val stub = files.single { it.name == "StrictContractServicePortalStub" }.toString()
        val dispatcher = files.single { it.name == "StrictContractServicePortalDispatcher" }.toString()

        assertTrue(dispatcher.contains("PortalCodec.requireCapabilityCount(granted.size, 1,"), dispatcher)
        assertTrue(dispatcher.contains("PortalCodec.requireCapabilityCount(granted.size, 0,"), dispatcher)
        assertTrue(dispatcher.contains("reader.inContext(\"label\")"), dispatcher)
        assertTrue(dispatcher.contains("reader.requireExhausted()"), dispatcher)
        assertTrue(dispatcher.contains("reader.map("), dispatcher)
        assertTrue(stub.contains("PortalCodec.Reader(result,"), stub)
        assertTrue(stub.contains(".requireExhausted()"), stub)
        assertTrue(stub.contains("reader.map("), stub)
        assertTrue(stub.contains("reader.inContext(\"label\")"), stub)
    }

    @Test
    fun `publisher rejects overlapping equal relative and symlinked output paths before mutating sentinels`() {
        val root = createTempDirectory("portal-publisher-overlap-")
        try {
            val childDest = dirWithFile(root.resolve("output/child"), "child.sentinel", "old-child")
            val parentSentinel = root.resolve("output/parent.sentinel")
            Files.writeString(parentSentinel, "old-parent")
            val childSentinel = childDest.resolve("child.sentinel")
            val stageA = dirWithFile(root.resolve("stage-a"), "new-a", "a")
            val stageB = dirWithFile(root.resolve("stage-b"), "new-b", "b")
            val symlinkAlias = root.resolve("link-output")
            Files.createSymbolicLink(symlinkAlias, root.resolve("output"))

            listOf(
                listOf(stageA to childDest, stageB to root.resolve("output")),
                listOf(stageA to root.resolve("output"), stageB to childDest),
                listOf(stageA to childDest, stageB to childDest.resolve("../child")),
                listOf(stageA to childDest, stageB to symlinkAlias),
                listOf(stageA to root.resolve("output"), root.resolve("output/nested-stage") to root.resolve("other-dest")),
            ).forEach { invalidPlan ->
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    GeneratedOutputPublisher.publish(invalidPlan)
                }
                val message = failure.message.orEmpty()
                assertTrue(message.contains("overlap") || message.contains("distinct"), message)
                assertTrue(Files.readString(parentSentinel) == "old-parent")
                assertTrue(Files.readString(childSentinel) == "old-child")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `publisher preserves prior sentinels on move failures and preserves primary and fatal errors across cleanup`() {
        val root = createTempDirectory("portal-publisher-faults-")
        try {
            val destA = dirWithFile(root.resolve("dest-a"), "sentinel-a", "old-a")
            val destB = dirWithFile(root.resolve("dest-b"), "sentinel-b", "old-b")
            val stageA = dirWithFile(root.resolve("stage-a"), "fresh-a", "new-a")
            val stageB = dirWithFile(root.resolve("stage-b"), "fresh-b", "new-b")

            // Failure during backup of second entry restores first destination sentinel via rollback.
            var backupFaultCount = 0
            val backupFailure = assertThrows(IOException::class.java) {
                GeneratedOutputPublisher.publish(
                    outputs = listOf(stageA to destA, stageB to destB),
                    moveDirectory = { src, dst ->
                        backupFaultCount++
                        if (backupFaultCount == 3) throw IOException("injected backup failure on B")
                        Files.move(src, dst)
                    },
                )
            }
            assertTrue(backupFailure.message == "injected backup failure on B")
            assertTrue(Files.readString(destA.resolve("sentinel-a")) == "old-a")
            assertTrue(Files.readString(destB.resolve("sentinel-b")) == "old-b")

            // Failure during second publication restores first destination sentinel via rollback.
            val stageA1 = dirWithFile(root.resolve("stage-a1"), "fresh-a", "new-a")
            val stageB1 = dirWithFile(root.resolve("stage-b1"), "fresh-b", "new-b")
            var moveCount = 0
            val rollbackSuccess = assertThrows(IOException::class.java) {
                GeneratedOutputPublisher.publish(
                    outputs = listOf(stageA1 to destA, stageB1 to destB),
                    moveDirectory = { src, dst ->
                        moveCount++
                        if (moveCount == 4) throw IOException("injected publish failure on B")
                        Files.move(src, dst)
                    },
                )
            }
            assertTrue(rollbackSuccess.message == "injected publish failure on B")
            assertTrue(Files.readString(destA.resolve("sentinel-a")) == "old-a")
            assertTrue(Files.readString(destB.resolve("sentinel-b")) == "old-b")
            assertFalse(Files.exists(destA.resolve("fresh-a")))

            // Rollback failure attaches suppressed exception to primary failure; fatal Error during rollback wins.
            val stageA2 = dirWithFile(root.resolve("stage-a2"), "fresh-a2", "new-a2")
            val stageB2 = dirWithFile(root.resolve("stage-b2"), "fresh-b2", "new-b2")
            val suppressedRollback = assertThrows(IOException::class.java) {
                var calls = 0
                GeneratedOutputPublisher.publish(
                    outputs = listOf(stageA2 to destA, stageB2 to destB),
                    moveDirectory = { src, dst ->
                        calls++
                        if (calls == 4) throw IOException("primary publish failure")
                        if (src.fileName.toString().startsWith(".")) throw IOException("rollback move failed")
                        Files.move(src, dst)
                    },
                )
            }
            assertTrue(suppressedRollback.message == "primary publish failure")
            assertTrue(suppressedRollback.suppressed.any { it.message == "rollback move failed" })

            val fatalRollback = assertThrows(AssertionError::class.java) {
                var calls = 0
                val destD = dirWithFile(root.resolve("dest-d"), "sentinel-d", "old-d")
                val destE = dirWithFile(root.resolve("dest-e"), "sentinel-e", "old-e")
                val stageD = dirWithFile(root.resolve("stage-d"), "fresh-d", "new-d")
                val stageE = dirWithFile(root.resolve("stage-e"), "fresh-e", "new-e")
                GeneratedOutputPublisher.publish(
                    outputs = listOf(stageD to destD, stageE to destE),
                    moveDirectory = { src, dst ->
                        calls++
                        if (calls == 4) throw IOException("primary failure")
                        if (src.fileName.toString().startsWith(".")) throw AssertionError("fatal rollback error")
                        Files.move(src, dst)
                    },
                )
            }
            assertTrue(fatalRollback.message == "fatal rollback error")
            assertTrue(fatalRollback.suppressed.any { it.message == "primary failure" })

            val stageA3 = dirWithFile(root.resolve("stage-a3"), "fresh-a3", "new-a3")
            val destC = dirWithFile(root.resolve("dest-c"), "sentinel-c", "old-c")
            val cleanupError = assertThrows(AssertionError::class.java) {
                GeneratedOutputPublisher.publish(
                    outputs = listOf(stageA3 to destC),
                    deleteDirectory = { throw AssertionError("fatal backup delete error") },
                )
            }
            assertTrue(cleanupError.message == "fatal backup delete error")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun dirWithFile(
        dir: Path,
        fileName: String,
        content: String,
    ): Path {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(fileName), content)
        return dir
    }
}

private interface CommonScalarService {
    fun uuid(value: java.util.UUID): java.util.UUID

    fun instant(value: java.time.Instant): java.time.Instant

    fun decimal(value: java.math.BigDecimal): java.math.BigDecimal
}

private interface EnumService {
    fun echo(value: TransportKind): TransportKind
}

private interface DeclaredExceptionService {
    @Throws(InvalidDocument::class)
    fun parse(value: String): String
}

private class InvalidDocument(
    message: String,
) : Exception(message)

private enum class TransportKind { FIRST, SECOND }
