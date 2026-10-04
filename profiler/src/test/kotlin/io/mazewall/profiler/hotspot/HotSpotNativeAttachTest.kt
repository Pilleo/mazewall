package io.mazewall.profiler.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.profiler.internal.hotspot.HotSpotAttachOptions
import io.mazewall.profiler.internal.hotspot.HotSpotBackend
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Explicit operator-enabled bridge acceptance; ordinary host unit suites need no Docker/kernel privileges. */
class HotSpotNativeAttachTest {
    @TempDir lateinit var directory: Path

    @Test
    @EnabledIfEnvironmentVariable(named = "MW_HOTSPOT_NATIVE_ATTACH_TEST", matches = "true")
    fun `owned Kotlin adapter captures real ordinary JVM and finishes through parent control pipe`() {
        val assets = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_ASSET_TEST_DIR")))
        val docker = listOf("docker", "--host", "unix:///var/run/docker.sock")
        val jdk = "eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6"
        val go = "golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84"
        val fixture = Path.of("../experiments/hotspot-agentless/fixtures/java/InlineOpenFixture.java").toAbsolutePath().normalize()
        val uid = Files.getAttribute(directory, "unix:uid").toString()
        val target = command(
            docker +
                listOf(
                    "run",
                    "--pull=never",
                    "-d",
                    "--user",
                    uid,
                    "--pid=host",
                    "--mount",
                    "type=bind,src=$directory,dst=/fixture",
                    "--mount",
                    "type=bind,src=$fixture,dst=/InlineOpenFixture.java,readonly",
                    jdk,
                    "java",
                    "-Xint",
                    "-XX:+UseSerialGC",
                    "/InlineOpenFixture.java",
                    "/fixture",
                    "/fixture/gate",
                    "/fixture/finish",
                ),
        ).trim()
        try {
            waitFor { command(docker + listOf("logs", target)).contains("ready ") }
            val pid = command(docker + listOf("inspect", "--format", "{{.State.Pid}}", target)).trim().toInt()
            val prefix =
                docker +
                    listOf(
                        "run",
                        "--pull=never",
                        "--rm",
                        "-i",
                        "--privileged",
                        "--pid=host",
                        "--mount",
                        "type=bind,src=$assets,dst=$assets,readonly",
                        "--mount",
                        "type=bind,src=/sys,dst=/sys,readonly",
                        go,
                    )
            HotSpotBackend().attach(Pid(pid), HotSpotAttachOptions(assets, prefix)).use { session ->
                assertFalse(requireNotNull(session.snapshot().evidence).finalized)
                Files.createFile(directory.resolve("gate"))
                waitFor { command(docker + listOf("logs", target)).contains("completed ") }
                val final = session.finish()
                val evidence = requireNotNull(final.evidence)
                assertTrue(evidence.finalized && evidence.terminalAccountingComplete && evidence.summary.physicalDrainComplete)
                assertFalse(final.pendingOwnership || evidence.collectionLossComplete)
                assertEquals(400, evidence.records.count { it.event.filename.contains("mw-inline-open-") })
                assertTrue(evidence.records.any { record -> record.evidence.frames.any { it.functionName.contains("InlineOpenFixture") } })
                Files.createFile(directory.resolve("finish"))
            }
        } finally {
            command(docker + listOf("rm", "-f", target))
        }
    }

    private fun command(argv: List<String>): String {
        val child = RealProcessLauncher.startProcess(argv)
        val output = child.inputStream.bufferedReader().use { it.readText() }
        check(child.waitFor(30, TimeUnit.SECONDS) && child.exitValue() == 0) { output }
        return output
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition()) {
            check(System.nanoTime() - deadline < 0) { "Native target fixture deadline expired" }
            Thread.sleep(50)
        }
    }
}
