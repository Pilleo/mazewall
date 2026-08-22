package io.mazewall.portal.codegen

import io.mazewall.portal.codegen.testapi.BadStream
import io.mazewall.portal.codegen.testapi.Overloads
import io.mazewall.portal.codegen.testapi.SampleFd
import io.mazewall.portal.codegen.testapi.SampleGeom
import io.mazewall.portal.codegen.testapi.SampleGreeter
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
        assertTrue(stub.contains("PortalCodec.encodeString"))
        assertFalse(stub.contains("Impl("))
        assertTrue(dispatcher.contains("SampleGreeterPortalDispatcher"))
        assertTrue(dispatcher.contains("impl.greet"))
        assertFalse(dispatcher.contains("Impl("))
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
        assertTrue(stub.contains("broker.invoke"))
        assertFalse(stub.contains("encodeString(fd)"))
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
    }

    @Test
    fun `plugin apply registers generatePortalStubs`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(PortalCodegenPlugin::class.java)
        assertTrue(project.tasks.findByName("generatePortalStubs") != null)
    }
}
