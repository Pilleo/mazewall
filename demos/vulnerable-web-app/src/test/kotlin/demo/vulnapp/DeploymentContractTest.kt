package demo.vulnapp

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeploymentContractTest {
    @Test
    fun `default artifact excludes executable deserialization fixtures`() {
        java.util.jar.JarFile(projectFile("build/libs/vulnerable-app.jar").toFile()).use { jar ->
            assertEquals(null, jar.getEntry("BOOT-INF/classes/demo/vulnapp/service/CustomGadget.class"))
            assertEquals(null, jar.getEntry("BOOT-INF/classes/demo/vulnapp/controller/ImportController.class"))
        }
    }

    @Test
    fun `profiling and enforcement share one named artifact volume with distinct access`() {
        val compose = Yaml().load<Map<String, Any>>(Files.readString(projectFile("compose.yml")))
        val services = compose.getValue("services") as Map<*, *>
        val profile = services["profile"] as Map<*, *>
        val protected = services["protected"] as Map<*, *>
        assertEquals(listOf("profile-artifacts:/app/profiles:rw"), profile["volumes"])
        assertEquals(listOf("profile-artifacts:/app/profiles:ro"), protected["volumes"])
        assertTrue((compose["volumes"] as? Map<*, *>)?.containsKey("profile-artifacts") == true)
        for (service in listOf(profile, protected)) {
            assertTrue((service["environment"] as List<*>).contains("JAVA_TOOL_OPTIONS=-Dmazewall.sbob.path=/app/profiles/sbob.json"))
        }
    }

    @Test
    fun `compose keeps host ports private while binding container servers for networking`() {
        val compose = Yaml().load<Map<String, Any>>(Files.readString(projectFile("compose.yml")))
        val services = compose.getValue("services") as Map<*, *>

        mapOf(
            "unprotected" to ("8082" to "8080"),
            "protected" to ("8081" to "8081"),
            "profile" to ("8083" to "8083"),
        ).forEach { (name, ports) ->
            val (hostPort, containerPort) = ports
            val service = services[name] as Map<*, *>
            val publishedPorts = service["ports"] as List<*>
            val environment = (service["environment"] as List<*>)
                .map { it.toString() }
                .associate { setting: String -> setting.substringBefore('=') to setting.substringAfter('=') }

            assertEquals(listOf("127.0.0.1:$hostPort:$containerPort"), publishedPorts)
            assertEquals("0.0.0.0", environment["SERVER_ADDRESS"])
            assertTrue(service.containsKey("healthcheck"), "$name must expose a container health check")
        }
    }

    @Test
    fun `container image creates exact Landlock policy roots before startup`() {
        val containerfile = Files.readString(projectFile("Containerfile"))

        assertTrue(containerfile.contains("/app/uploads"))
        assertTrue(containerfile.contains("/app/data"))
        assertTrue(containerfile.contains("chown"), "policy roots must have explicit ownership")
    }

    private fun projectFile(name: String): Path = Path.of(name)
}
