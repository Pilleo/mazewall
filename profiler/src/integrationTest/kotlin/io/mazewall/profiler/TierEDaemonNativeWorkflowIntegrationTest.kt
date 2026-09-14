package io.mazewall.profiler.api

import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@NeedsFreshJvm
class TierEDaemonNativeWorkflowIntegrationTest {
    @Test
    fun `daemon controls an agent loaded workload through attach detach and shutdown`() {
        val agent = (System.getProperty("tier.e.agent") ?: System.getenv("TIER_E_AGENT_LIBRARY"))?.let(Path::of)
        val workloadClasspath = System.getProperty("tier.e.workloadClasspath")
            ?: System.getenv("TIER_E_WORKLOAD_CLASSPATH")
        val uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as? Int
        assumeTrue(uid == 0 && agent != null && workloadClasspath != null, "rootful Tier E fixture environment is required")
        requireNotNull(agent)
        requireNotNull(workloadClasspath)

        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val definitions = Files.createTempFile("tier-e-daemon-workflow", ".mzsd")
        val daemon = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "io.mazewall.profiler.tierE.daemon.TierEKotlinDaemonMain",
        ).redirectErrorStream(true).start()
        val transcript = StringBuilder()
        val daemonReader = daemon.inputStream.bufferedReader()
        val firstLine = daemonReader.readLine() ?: error("daemon exited before announcing a port")
        transcript.appendLine(firstLine)
        val port = Regex("listening port=([0-9]+)")
            .find(firstLine)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("daemon did not announce a TCP port: $firstLine")

        val markerOffset = ElfSymbolOffset.resolve(agent, "mazewall_stack_marker")
        val workload = ProcessBuilder(
            java,
            "-agentpath:$agent=definitions=$definitions,live-definitions,session=4d5a,mode=full-stream",
            "-cp",
            workloadClasspath,
            "AgentWorkload",
            "0",
            "platform",
            "20",
            port.toString(),
            agent.toString(),
            markerOffset.toString(16),
            "4d5a",
        ).redirectErrorStream(true).start()

        try {
            val workloadOutput = workload.inputStream.bufferedReader().readText()
            val workloadStatus = workload.waitFor()
            transcript.append(workloadOutput)
            Files.createDirectories(Path.of("build/tier-e-evidence"))
            Files.writeString(Path.of("build/tier-e-evidence/tier-e-daemon-native-workflow.txt"), transcript)

            assertEquals(0, workloadStatus, workloadOutput)
            assertTrue(workloadOutput.contains("tier-e-workflow: OK attached"), workloadOutput)
            assertTrue(workloadOutput.contains("tier-e-workflow: OK engine=active"), workloadOutput)
            assertTrue(workloadOutput.contains("tier-e-workflow: detached"), workloadOutput)
            assertTrue(workloadOutput.contains("tier-e-workflow: shutdown"), workloadOutput)
            assertTrue(Regex("stack-captures=[1-9][0-9]*").containsMatchIn(workloadOutput), workloadOutput)
            assertTrue(
                Files.readAllBytes(definitions).let { it.size > 32 && it.copyOfRange(0, 4).contentEquals(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte())) },
            )
        } finally {
            if (workload.isAlive) workload.destroyForcibly()
            if (daemon.isAlive) daemon.destroyForcibly()
            daemon.waitFor(5, TimeUnit.SECONDS)
            Files.deleteIfExists(definitions)
        }
    }
}
