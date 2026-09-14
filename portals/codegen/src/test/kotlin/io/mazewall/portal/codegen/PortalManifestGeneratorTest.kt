package io.mazewall.portal.codegen

import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.codegen.testapi.SampleGreeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PortalManifestGeneratorTest {
    interface StringListService {
        fun values(input: List<String>): List<String>
    }

    interface IntegerListService {
        fun values(input: List<Int>): List<Int>
    }

    @Test
    fun `generates a manifest from explicit worker registration`() {
        val manifest =
            PortalManifestGenerator.generate(
                SampleGreeter::class.java,
                PortalServiceRegistration(
                    serviceId = "sample-greeter",
                    contractName = SampleGreeter::class.java.name,
                    implementationName = "fixture.SampleGreeterImpl",
                    isolationGroup = "sample",
                    instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
                    policyProfile = "restricted",
                    workerArtifact = "sample.portal-worker.jar",
                ),
            )

        assertEquals(SampleGreeter::class.java.name, manifest.contractName)
        assertEquals("fixture.SampleGreeterImpl", manifest.implementationName)
        assertEquals("sample", manifest.isolationGroup)
        assertNotEquals("sha256:", manifest.schemaId)
    }

    @Test
    fun `schema fingerprint includes parameterized boundary shape`() {
        val stringManifest = PortalManifestGenerator.schemaFingerprint(StringListService::class.java)
        val integerManifest = PortalManifestGenerator.schemaFingerprint(IntegerListService::class.java)

        assertNotEquals(stringManifest, integerManifest)
    }

    private fun registrationFor(contract: Class<*>): PortalServiceRegistration =
        PortalServiceRegistration(
            serviceId = contract.simpleName,
            contractName = contract.name,
            implementationName = "fixture.${contract.simpleName}Impl",
            isolationGroup = contract.simpleName,
            instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
            policyProfile = "restricted",
            workerArtifact = "fixture.portal-worker.zip",
        )
}
