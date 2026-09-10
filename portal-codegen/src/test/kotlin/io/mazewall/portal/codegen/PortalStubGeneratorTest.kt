package io.mazewall.portal.codegen

import io.mazewall.portal.codegen.testapi.BadStream
import io.mazewall.portal.codegen.testapi.InheritedService
import io.mazewall.portal.codegen.testapi.KotlinDataDtoService
import io.mazewall.portal.codegen.testapi.MethodIdShadowService
import io.mazewall.portal.codegen.testapi.Overloads
import io.mazewall.portal.codegen.testapi.SampleFd
import io.mazewall.portal.codegen.testapi.SampleGeom
import io.mazewall.portal.codegen.testapi.SampleGreeter
import io.mazewall.portal.codegen.testapi.UnreadablePojoService
import io.mazewall.portal.codegen.testapi.ValidPojoService
import io.mazewall.portal.codegen.testapi.WrongReaderPojoService
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortalStubGeneratorTest {
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
    fun `overloads are rejected`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                PortalStubGenerator.generate(Overloads::class.java)
            }
        assertTrue(ex.message!!.contains("overloads"))
        assertTrue(ex.message!!.contains("echo(java.lang.String)"), ex.message)
        assertTrue(ex.message!!.contains("echo(int)"), ex.message)
    }

    @Test
    fun `effective inherited methods and override are generated once`() {
        val files = PortalStubGenerator.generate(InheritedService::class.java)
        val stub = files.single { it.name == "InheritedServicePortalStub" }.toString()
        val dispatcher = files.single { it.name == "InheritedServicePortalDispatcher" }.toString()

        assertTrue(stub.contains("override fun inherited"), stub)
        assertTrue(stub.contains("override fun overridden"), stub)
        assertTrue(dispatcher.split(".overridden").size == 2, dispatcher)
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
    fun `method id constant cannot be shadowed by its identically named parameter`() {
        val method = MethodIdShadowService::class.java.getDeclaredMethod("pinned", String::class.java)
        assertTrue(PortalStubGenerator.methodId(MethodIdShadowService::class.java, method) == 654_749_016)

        val stub =
            PortalStubGenerator
                .generate(MethodIdShadowService::class.java)
                .single { it.name == "MethodIdShadowServicePortalStub" }
                .toString()
        assertTrue(stub.contains("pinned(PINNED_METHOD_ID: String)"), stub)
        assertTrue(stub.contains("PortalMethod.Generated(Companion.PINNED_METHOD_ID)"), stub)
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
    fun `plugin apply registers generatePortalStubs`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(PortalCodegenPlugin::class.java)
        assertTrue(project.tasks.findByName("generatePortalStubs") != null)
    }
}
