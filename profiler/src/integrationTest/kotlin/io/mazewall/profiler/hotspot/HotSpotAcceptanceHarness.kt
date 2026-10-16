package io.mazewall.profiler.hotspot

import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.testing.awaitCondition
import io.mazewall.testing.containers.ContainerEndpoint
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val CLEANUP_WAIT_SECONDS = 5L
private const val OUTPUT_JOIN_SECONDS = 5L
private const val PROCESS_REAP_SECONDS = 5L
private const val ACCEPTANCE_DEADLINE_SECONDS = 30L

internal class HotSpotAcceptanceHarness(
    private val endpoint: ContainerEndpoint,
    val directory: Path,
) : AutoCloseable {
    val assets: Path = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_ASSET_TEST_DIR")) { "Select a verified installed HotSpot asset directory" })
    val run: String = UUID
        .randomUUID()
        .toString()
        .replace("-", "")
        .take(16)
    private var target: String? = null
    private val collectorNames = mutableListOf<String>()
    val pid: Int get() = command(endpoint.command("inspect", "--format", "{{.State.Pid}}", checkNotNull(target))).trim().toInt()

    fun start(operations: Int = 400) {
        require(endpoint.resolvedUri?.startsWith("unix://") == true) { "Host PID/shared assets require a local rootful endpoint" }
        val source = directory.resolve("HotSpotAcceptanceFixture.java")
        requireNotNull(javaClass.getResourceAsStream("/hotspot/HotSpotAcceptanceFixture.java")).use { Files.copy(it, source) }
        val uid = Files.getAttribute(directory, "unix:uid").toString()
        target = command(
            endpoint.command(
                "run",
                "--pull=never",
                "-d",
                "--user",
                uid,
                "--pid=host",
                "--mount",
                "type=bind,src=$directory,dst=/fixture",
                JDK,
            "java",
                "-Xint",
                "-XX:+UseSerialGC",
                "/fixture/HotSpotAcceptanceFixture.java",
                "/fixture",
                run,
                operations.toString(),
            ),
        ).trim()
        await { command(endpoint.command("logs", checkNotNull(target))).contains("ready ") }
    }

    fun options(
        retained: Int = 4096,
        privileged: Boolean = true,
        timeout: Duration = Duration.ofSeconds(10),
    ): HotSpotOptions {
        val name = "hs-collector-$run-${collectorNames.size}"
        collectorNames.add(name)
        val uid = Files.getAttribute(directory, "unix:uid").toString()
        val prefix =
            endpoint.command(
                "run",
                "--pull=never",
                "--rm",
                "-i",
                "--name",
                name,
                "--pid=host",
                "--mount",
                "type=bind,src=$assets,dst=$assets,readonly",
                "--mount",
                "type=bind,src=/sys,dst=/sys,readonly",
            ) +
            (if (privileged) listOf("--privileged") else listOf("--user", uid)) +
            GO
        return HotSpotOptions(assets, prefix, readinessTimeout = timeout, maxRetainedOccurrences = retained)
    }

    fun prepare() {
        Files.createFile(directory.resolve("prepare"))
        await { Files.exists(directory.resolve("prepared")) }
    }

    fun measure() {
        Files.createFile(directory.resolve("gate"))
        await { Files.exists(directory.resolve("target.json")) }
    }

    fun killTarget() {
        command(endpoint.command("kill", checkNotNull(target)))
    }

    fun restartTarget(): Int {
        command(endpoint.command("restart", checkNotNull(target)))
        await { command(endpoint.command("logs", "--tail", "1", checkNotNull(target))).contains("ready ") }
        return pid
    }

    fun stopStandalone() {
        command(endpoint.command("kill", "--signal", "SIGTERM", collectorNames.last()))
    }

    fun assertCollectorsGone() {
        for (name in collectorNames) {
            await { runCommand(endpoint.command("inspect", name)).first != 0 }
        }
    }

    override fun close() {
        for (name in collectorNames + listOfNotNull(target)) {
            runCommand(endpoint.command("rm", "-f", name))
        }
    }

    companion object {
        const val JDK = "eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6"
        const val GO = "golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84"

        fun command(
            argv: List<String>,
            timeoutSeconds: Long = 30,
        ): String {
            val result = runCommand(argv, timeoutSeconds)
            check(result.first == 0) { result.second }
            return result.second
        }

        fun runCommand(
            argv: List<String>,
            timeoutSeconds: Long = 30,
        ): Pair<Int, String> {
            val child = RealProcessLauncher.startProcess(argv)
            val result = java.util.concurrent.CompletableFuture
                .supplyAsync { child.inputStream.bufferedReader().use { it.readText() } }
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                check(child.waitFor(PROCESS_REAP_SECONDS, TimeUnit.SECONDS)) { "Owned command did not retire" }
                error("Container command deadline exceeded")
            }
            return child.exitValue() to result.get(OUTPUT_JOIN_SECONDS, TimeUnit.SECONDS)
        }

        fun await(condition: () -> Boolean) {
            awaitCondition(Duration.ofSeconds(ACCEPTANCE_DEADLINE_SECONDS), "native acceptance fixture readiness", condition)
        }
    }
}
