package io.mazewall.portal.worker

import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalServiceManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PortalWorkerServiceRegistryTest {
    @Test
    fun `constructs each group member once and retains its worker-local instances`() {
        var constructed = 0
        val manifest = manifest()
        val registry = PortalWorkerServiceRegistry(listOf(manifest), mapOf("fixture.Impl" to PortalWorkerFactory { ++constructed }))

        val first = registry.group("fixture")
        val second = registry.group("fixture")

        assertEquals(1, constructed)
        assertSame(first, second)
        assertEquals(1, first.getValue("fixture-service"))
    }

    @Test
    fun `rejects missing worker factory before construction`() {
        val failure = assertFailsWith<IllegalArgumentException> { PortalWorkerServiceRegistry(listOf(manifest()), emptyMap()) }
        kotlin.test.assertTrue(failure.message!!.contains("no worker factory"), failure.message)
    }

    @Test
    fun `reflective factory constructs and shares ordinary worker local collaborators`() {
        val manifest = manifest(implementationName = Parser::class.java.name)
        val registry =
            PortalWorkerServiceRegistry(
                listOf(manifest),
                mapOf(Parser::class.java.name to PortalReflectiveWorkerFactory()),
                javaClass.classLoader,
            )

        val parser = registry.group("fixture").getValue("fixture-service") as Parser

        assertEquals("settings", parser.settings.value)
    }

    @Test
    fun `group members share a worker local collaborator instance`() {
        val parser = manifest("fixture-parser", Parser::class.java.name)
        val validator = manifest("fixture-validator", Validator::class.java.name)
        val registry =
            PortalWorkerServiceRegistry(
                listOf(parser, validator),
                mapOf(
                    Parser::class.java.name to PortalReflectiveWorkerFactory(),
                    Validator::class.java.name to PortalReflectiveWorkerFactory(),
                ),
                javaClass.classLoader,
            )

        val group = registry.group("fixture")

        assertSame((group.getValue("fixture-parser") as Parser).settings, (group.getValue("fixture-validator") as Validator).settings)
    }

    @Test
    fun `reflective factory rejects an interface collaborator with a complete diagnostic`() {
        val manifest = manifest(implementationName = NeedsRepository::class.java.name)
        val registry =
            PortalWorkerServiceRegistry(
                listOf(manifest),
                mapOf(NeedsRepository::class.java.name to PortalReflectiveWorkerFactory()),
                javaClass.classLoader,
            )

        val failure = assertFailsWith<IllegalArgumentException> { registry.group("fixture") }

        kotlin.test.assertTrue(failure.message!!.contains(Repository::class.java.name), failure.message)
        kotlin.test.assertTrue(failure.message!!.contains("no concrete binding"), failure.message)
    }

    private fun manifest(
        serviceId: String = "fixture-service",
        implementationName: String = "fixture.Impl",
    ): PortalServiceManifest =
        PortalServiceManifest(
            serviceId = PortalServiceId(serviceId),
            contractName = "fixture.Contract",
            implementationName = implementationName,
            dispatcherName = "fixture.Dispatcher",
            schemaId = "schema",
            isolationGroup = "fixture",
            instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
            policyProfile = "restricted",
            workerArtifact = "fixture-worker",
        )

    class Parser(
        val settings: Settings,
    )

    class Settings {
        val value: String = "settings"
    }

    class Validator(
        val settings: Settings,
    )

    interface Repository

    class NeedsRepository(
        val repository: Repository,
    )
}
