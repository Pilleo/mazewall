package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortalServiceRegistryTest {
    interface Parser

    @Test
    fun `requires an explicit id for multiple contract bindings`() {
        val registry = PortalServiceRegistry(listOf(manifest("first"), manifest("second")))

        assertThrows(PortalServiceAmbiguousException::class.java) { registry.resolve(Parser::class.java) }
        assertEquals("second", registry.resolve(Parser::class.java, PortalServiceId("second")).serviceId.value)
    }

    @Test
    fun `rejects incompatible services in one isolation group`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalServiceRegistry(
                listOf(
                    manifest("first"),
                    manifest("second").copy(policyProfile = "different"),
                ),
            )
        }
    }

    @Test
    fun `reports a missing binding`() {
        assertThrows(PortalServiceNotFoundException::class.java) {
            PortalServiceRegistry(emptyList()).resolve(Parser::class.java)
        }
    }

    private fun manifest(id: String): PortalServiceManifest =
        PortalServiceManifest(
            serviceId = PortalServiceId(id),
            contractName = Parser::class.java.name,
            implementationName = "fixture.ParserImpl$id",
            dispatcherName = "fixture.ParserPortalDispatcher",
            schemaId = "schema-$id",
            isolationGroup = "parsers",
            instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
            policyProfile = "restricted",
            workerArtifact = "/tmp/worker.jar",
        )
}
