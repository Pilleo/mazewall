package io.mazewall.portal

import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ExecutionGeneration
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequiredWorkerGenerationTest {
    @TempDir var directory: Path? = null

    @Test
    fun `passive diagnostics expose only the generation accepted by actual worker readiness`() {
        var child: Process? = null
        var requested: String? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                requested = args[args.indexOf("--worker-generation") + 1]
                return RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also { child = it }
            }
        }
        val broker = ProcessBroker(launcher = launcher)
        try {
            repeat(3) {
                assertEquals(ExecutionDiagnosticState.UNKNOWN, broker.diagnostics().state)
                assertIs<ExecutionGeneration.Unavailable>(broker.diagnostics().generation)
                assertNull(child, "diagnostic polling cannot start the lazy worker")
            }
            broker.start()
            val ready = broker.diagnostics()
            assertEquals(ExecutionDiagnosticState.READY, ready.state)
            val generation = assertIs<ExecutionGeneration.PortalInputs>(ready.generation)
            assertEquals(requested, "${generation.inputsSha256}:${generation.manifestsSha256}:${generation.runtimeSha256}")
            assertEquals(1, ready.liveWorkers)
            assertEquals(ready, broker.diagnostics(), "polling cannot dispatch work or replace the worker")
            assertFalse(ready.retirementComplete)
        } finally {
            broker.close()
        }
        assertFalse(checkNotNull(child).isAlive)
        val closed = broker.diagnostics()
        assertEquals(ExecutionDiagnosticState.CLOSED, closed.state)
        assertTrue(closed.retirementComplete)
        assertEquals(0, closed.liveWorkers)
    }

    @Test
    fun `actual worker rejects changed expected generation after parent ACK`() {
        var child: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                val altered = args.toMutableList()
                val index = altered.indexOf("--worker-generation")
                val original = altered[index + 1]
                altered[index + 1] = (if (original[0] == '0') "1" else "0") + original.substring(1)
                return RealProcessLauncher.startProcess(altered, redirectErrorStream, preparation).also { child = it }
            }
        }
        ProcessBroker(launcher = launcher).use { broker ->
            assertFailsWith<Exception> { broker.start() }
            assertFalse(checkNotNull(child).isAlive)
            assertEquals(0, broker.trackedWorkers())
            assertEquals(0, broker.provisionalWorkers())
            assertIs<ExecutionGeneration.Unavailable>(broker.diagnostics().generation, "rejected readiness cannot publish an input generation")
        }
    }

    @Test
    fun `matching parent ACK followed only by legacy sentinel never publishes pool`() {
        val fixture = sentinelFixture()
        var child: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                val altered = args.toMutableList()
                val main = altered.indexOf("io.mazewall.portal.worker.PortalWorkerMain")
                altered[main] = "fixture.AckSentinelWorker"
                val cp = altered.indexOf("-cp")
                altered[cp + 1] += java.io.File.pathSeparator + fixture
                return RealProcessLauncher.startProcess(altered, redirectErrorStream, preparation).also { child = it }
            }
        }
        ProcessBroker(launcher = launcher, limits = PortalRuntimeLimits(startupTimeoutMs = 2500)).use { broker ->
            val failure = assertFailsWith<PortalReadTimeoutException> { broker.start() }
            assertTrue(failure.suppressed.any { it.message.orEmpty().contains("MAZEWALL_PORTAL_WORKER_READY") }, "ACK-only fixture must actually publish its legacy sentinel")
            assertFalse(checkNotNull(child).isAlive)
            assertEquals(0, broker.trackedWorkers())
            assertEquals(0, broker.provisionalWorkers())
        }
    }

    private fun sentinelFixture(): Path {
        val directory = requireNotNull(directory) { "JUnit must initialize the temporary directory" }
        val source = directory.resolve("AckSentinelWorker.java")
        Files.writeString(
            source,
            """
            package fixture;
            import io.mazewall.portal.*;
            import io.mazewall.platform.socket.RealSocketManager;
            public class AckSentinelWorker {
                public static void main(String[] args) throws Exception {
                    String session = args[java.util.Arrays.asList(args).indexOf("--parent-session") + 1];
                    var sockets = RealSocketManager.INSTANCE;
                    try (var channel = new PortalChannel(sockets.connect(args[0]), sockets, 512, 512, 0)) {
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                        var receive = java.util.Arrays.stream(PortalChannel.class.getMethods()).filter(m -> m.getName().startsWith("receiveControl-")).findFirst().orElseThrow();
                        var send = java.util.Arrays.stream(PortalChannel.class.getMethods()).filter(m -> m.getName().startsWith("sendControl-")).findFirst().orElseThrow();
                        receive.invoke(channel, deadline);
                        send.invoke(channel, new PortalParentAuthorization(session, ProcessHandle.current().pid()).acknowledgement(), deadline);
                        System.out.println("MAZEWALL_PORTAL_WORKER_READY");
                        System.out.flush();
                        Thread.sleep(10000);
                    }
                }
            }
        """.trimIndent(),
        )
        val classes = Files.createDirectory(directory.resolve("sentinel-classes"))
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, compiler.run(null, null, null, "-classpath", System.getProperty("io.mazewall.portal.worker.classpath"), "-d", classes.toString(), source.toString()))
        return classes
    }
}
