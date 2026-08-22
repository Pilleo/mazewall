package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.atomic.AtomicInteger

class JvmChildProcessTest {
    @Test
    fun `commandLine includes java, heap, classpath, main, and args`() {
        val spec =
            JvmChildSpec(
                mainClass = "io.mazewall.ExampleMain",
                mainArgs = listOf("/tmp/sock"),
                maxHeap = "32m",
                extraJvmArgs = listOf("-Dfoo=bar"),
                javaAgents = JavaAgentSelection.None,
            )
        val cmd = JvmChildProcess.commandLine(spec)
        assertEquals(System.getProperty("java.home") + "/bin/java", cmd[0])
        assertTrue(cmd.contains("--enable-native-access=ALL-UNNAMED"))
        assertTrue(cmd.contains("-Xmx32m"))
        assertTrue(cmd.contains("-Dfoo=bar"))
        val cpIndex = cmd.indexOf("-cp")
        assertTrue(cpIndex >= 0)
        assertEquals("io.mazewall.ExampleMain", cmd[cpIndex + 2])
        assertEquals("/tmp/sock", cmd.last())
        assertFalse(cmd.any { it.startsWith("-javaagent:") })
    }

    @Test
    fun `start delegates to ProcessLauncher`() {
        val seen = mutableListOf<List<String>>()
        val launcher =
            object : ProcessLauncher {
                override fun startProcess(args: List<String>, redirectErrorStream: Boolean): Process {
                    seen.add(args)
                    return DummyProcess()
                }

                override fun addShutdownHook(hook: Thread) = Unit

                override fun removeShutdownHook(hook: Thread) = Unit

                override fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path =
                    Path.of("/tmp/x")

                override fun createTempDirectory(dir: Path, prefix: String, vararg attrs: FileAttribute<*>): Path =
                    dir.resolve("x")

                override fun deleteIfExists(path: Path): Boolean = true

                override fun exists(path: Path): Boolean = true
            }
        val proc =
            JvmChildProcess.start(
                launcher,
                JvmChildSpec("com.example.Main", javaAgents = JavaAgentSelection.None),
            )
        assertEquals(1, seen.size)
        assertTrue(seen[0].contains("com.example.Main"))
        assertEquals(0, proc.waitFor())
    }

    @Test
    fun `stdout pump counts down on sentinel`() {
        val process = DummyProcess(stdout = "noise\nREADY_OK\nmore\n")
        val lines = mutableListOf<String>()
        val pump =
            JvmChildProcess.startStdoutPump(
                process,
                readySentinel = "READY_OK",
                onLine = { lines.add(it) },
                threadName = "test-child-stdout",
            )
        assertTrue(JvmChildProcess.awaitReady(pump, 5))
        pump.thread.join(5_000)
        assertTrue(lines.contains("READY_OK"))
    }

    @Test
    fun `JacocoOnly keeps only jacoco agents from this JVM`() {
        val selected = JvmChildProcess.selectedJavaAgents(JavaAgentSelection.JacocoOnly)
        assertTrue(selected.all { it.startsWith("-javaagent:") && it.contains("jacoco") })
        val none = JvmChildProcess.selectedJavaAgents(JavaAgentSelection.None)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `unix endpoint falls back to tmp when sun_path is too long`() {
        val calls = AtomicInteger()
        val launcher =
            object : ProcessLauncher {
                override fun startProcess(args: List<String>, redirectErrorStream: Boolean) = DummyProcess()

                override fun addShutdownHook(hook: Thread) = Unit

                override fun removeShutdownHook(hook: Thread) = Unit

                override fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path {
                    calls.incrementAndGet()
                    return Path.of("/" + "n".repeat(200), "dir")
                }

                override fun createTempDirectory(dir: Path, prefix: String, vararg attrs: FileAttribute<*>): Path {
                    calls.incrementAndGet()
                    return dir.resolve("short")
                }

                override fun deleteIfExists(path: Path): Boolean = true

                override fun exists(path: Path): Boolean = true
            }
        val endpoint = PrivateUnixEndpoint.create(launcher, "mazewall-test-", "ipc.sock")
        assertTrue(PrivateUnixEndpoint.utf8Length(endpoint.path) <= PrivateUnixEndpoint.MAX_SUN_PATH_BYTES)
        assertEquals(Path.of("/tmp/short"), endpoint.dir)
        assertTrue(calls.get() >= 2)
    }

    private class DummyProcess(stdout: String = "") : Process() {
        private val input = stdout.byteInputStream()

        override fun getOutputStream() = java.io.ByteArrayOutputStream()

        override fun getInputStream() = input

        override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }
}
