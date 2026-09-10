package demo.vulnapp

import demo.vulnapp.service.FileService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import kotlin.test.assertNotNull

@SpringBootTest(properties = ["mazewall.profile=true", "mazewall.enabled=false"])
class VulnAppApplicationTests {
    companion object {
        init {
            System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
            val output = Files.createTempDirectory("mazewall-web-context-").resolve("sbob.json")
            System.setProperty("mazewall.sbob.path", output.toString())
        }
    }

    @Autowired(required = false)
    private val fileService: FileService? = null

    @Test
    fun contextLoads() {
    }

    @Test
    fun testProfilingTrigger() {
        val service = assertNotNull(fileService)
        val result = service.runCommand("echo Hello Profiler")
        kotlin.test.assertTrue(result.contains("Executed"))
    }
}
