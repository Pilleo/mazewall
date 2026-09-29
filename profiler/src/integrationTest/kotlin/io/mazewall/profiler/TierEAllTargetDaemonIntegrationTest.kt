package io.mazewall.profiler.api

import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the actual separate Kotlin daemon and its completed shared-file handoff. */
@NeedsFreshJvm
class TierEAllTargetDaemonIntegrationTest {
    @Test
    fun `registered agent target exports attributed and ambient events through helper process`() {
        val agent = (System.getProperty("tier.e.agent") ?: System.getenv("TIER_E_AGENT_LIBRARY"))?.let(Path::of)
        val workloadClasspath = System.getProperty("tier.e.workloadClasspath") ?: System.getenv("TIER_E_WORKLOAD_CLASSPATH")
        assumeTrue(agent != null && workloadClasspath != null, "rootful Tier E native fixture is required")
        requireNotNull(agent)
        requireNotNull(workloadClasspath)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val root = Files.createTempDirectory("tier-e-all-target-daemon-")
        val socket = root.resolve("control.sock")
        val definitions = root.resolve("definitions.mzse")
        val input = root.resolve("input")
        Files.writeString(input, "example")
        val firstGate = root.resolve("first.gate")
        val secondGate = root.resolve("second.gate")
        val ready = root.resolve("ready")
        val workload = ProcessBuilder(
            java,
            "-agentpath:$agent=session=4d5a,session-bound-definitions,definitions=$definitions,mode=full-stream",
            "-cp",
            workloadClasspath,
            "TierEAllTargetWorkload",
            firstGate.toString(),
            secondGate.toString(),
            ready.toString(),
            input.toString(),
            root.resolve("done").toString(),
        ).inheritIO().start()
        val pid = workload.pid()
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        val processStart = stat.substringAfterLast(") ").split(' ')[19].toLong()
        val markerOffset = ElfSymbolOffset.resolve(agent, "mazewall_stack_marker")
        val registration = root.resolve("target.properties")
        Files.writeString(
            registration,
            "version=1\nregistration_id=orders\npid=$pid\nsession_tag=4d5a\nprocess_incarnation=$processStart\n" +
                "marker_path=$agent\nmarker_offset=${markerOffset.toString(16)}\nartifact_file=definitions.mzse\n",
        )
        val daemon = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "io.mazewall.profiler.tierE.daemon.TierEKotlinDaemonMain")
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment()["TIER_E_TARGET_REGISTRATION"] = registration.toString()
                builder.environment()["TIER_E_DEFINITION_ROOT"] = root.toString()
                builder.environment()["TIER_E_EVIDENCE_ROOT"] = root.toString()
                builder.environment()["TIER_E_SOCKET"] = socket.toString()
                builder.environment()["TIER_E_ALL_TARGET_CAPTURE"] = "1"
            }.start()
        try {
            val readyLine = daemon.inputStream.bufferedReader().readLine()
            assertTrue(readyLine?.contains("listening socket=$socket") == true, readyLine)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                client.connect(UnixDomainSocketAddress.of(socket))
                val command = DataOutputStream(Channels.newOutputStream(client))
                val reply = DataInputStream(Channels.newInputStream(client))
                command.writeUTF("ATTACH $pid uprobe $agent ${markerOffset.toString(16)} 4d5a full-stream")
                command.flush()
                assertTrue(reply.readUTF().startsWith("OK attached"))
                Files.writeString(firstGate, "go")
                val deadline = System.nanoTime() + 20_000_000_000L
                while (!Files.exists(ready) && workload.isAlive && System.nanoTime() < deadline) Thread.sleep(2)
                assertTrue(Files.exists(ready), "managed first-stage workload never completed")
                Files.writeString(secondGate, "go")
                assertTrue(workload.waitFor(30, TimeUnit.SECONDS), "JVM workload did not terminate")
                assertEquals(0, workload.exitValue())

                val evidence = root.resolve("orders-19802.events.jsonl")
                val terminal = root.resolve("orders-19802.tier-e.json")
                val terminalDeadline = System.nanoTime() + 30_000_000_000L
                while ((!Files.exists(evidence) || !Files.exists(terminal)) && daemon.isAlive && System.nanoTime() < terminalDeadline) Thread.sleep(10)
                assertTrue(Files.exists(evidence) && Files.exists(terminal), "daemon did not publish both owned terminal artifacts")
                val rows = Files.readAllLines(evidence).map { Json.parseToJsonElement(it).jsonObject }
                val footer = rows.last()
                assertEquals((rows.size - 2).toString(), footer.getValue("capture_admitted_count").jsonPrimitive.content)
                assertEquals((rows.size - 2).toString(), footer.getValue("capture_submitted_count").jsonPrimitive.content)
                assertEquals("true", footer.getValue("capture_complete").jsonPrimitive.content)
                assertEquals("true", footer.getValue("export_complete").jsonPrimitive.content)
                assertEquals("false", footer.getValue("policy_eligibility").jsonPrimitive.content)
                assertTrue(rows.any { it["resolution_status"]?.jsonPrimitive?.content == "NO_ACTIVE_INVOCATION" })
                val methodSets = rows
                    .filter { it["resolution_status"]?.jsonPrimitive?.content == "RESOLVED" }
                    .groupBy { it.getValue("syscall_number").jsonPrimitive.content }
                    .values
                    .map { group ->
                        group
                            .flatMap { row ->
                            row.getValue("frames").jsonArray.map { frame ->
                                frame.jsonObject
                                    .getValue("method_name")
                                    .jsonPrimitive.content
                            }
                        }.toSet()
                    }
                assertTrue(methodSets.any { "readConfiguration" in it && "loadTemplate" in it })
                command.writeUTF("SHUTDOWN")
                command.flush()
                assertTrue(reply.readUTF().startsWith("OK shutting down"))
            }
        } finally {
            if (workload.isAlive) workload.destroyForcibly()
            if (daemon.isAlive) daemon.destroyForcibly()
            daemon.waitFor(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }
}
