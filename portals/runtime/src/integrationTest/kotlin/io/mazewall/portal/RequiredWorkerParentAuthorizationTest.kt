package io.mazewall.portal

import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequiredWorkerParentAuthorizationTest {
    @Test
    fun `native worker waits for parent before installing its additional process baseline`() {
        var beforeAuthorization = -1
        var child: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val launched = RealProcessLauncher.startProcess(args, redirectErrorStream)
                child = launched
                val pending = CompletableFuture.supplyAsync { launched.inputStream.bufferedReader().readLine() }
                assertEquals("MAZEWALL_PORTAL_PARENT_AUTH_PENDING", pending.get(5, TimeUnit.SECONDS))
                beforeAuthorization = filterCount(launched.pid())
                // Parent has not returned from acquisition, attached its controls or sent a permit.
                return launched
            }
        }
        val broker = ProcessBroker(launcher = launcher)
        try {
            broker.start()
            assertTrue(beforeAuthorization >= 0)
            assertTrue(filterCount(checkNotNull(child).pid()) > beforeAuthorization, "additional process filter appears only after parent permit")
            assertEquals("authorized", broker.echo("authorized"))
        } finally {
            try {
                broker.close()
            } finally {
                child?.let {
                    if (it.isAlive) {
                        it.destroyForcibly()
                        it.waitFor(5, TimeUnit.SECONDS)
                    }
                }
            }
        }
    }

    @Test
    fun `legacy sentinel cannot substitute for required parent acknowledgement`() {
        var child: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val altered = args.toMutableList()
                for (flag in listOf("--parent-session", "--parent-timeout-ms")) {
                    val index = altered.indexOf(flag)
                    check(index >= 0)
                    altered.removeAt(index + 1)
                    altered.removeAt(index)
                }
                return RealProcessLauncher.startProcess(altered, redirectErrorStream).also { child = it }
            }
        }
        ProcessBroker(launcher = launcher).use { broker ->
            assertFailsWith<IllegalArgumentException> { broker.start() }
            assertFalse(checkNotNull(child).isAlive)
            assertEquals(0, broker.trackedWorkers())
        }
    }

    @Test
    fun `wrong required session aborts worker startup and reaps the acquired child`() {
        var child: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val altered = args.toMutableList()
                val sessionIndex = altered.indexOf("--parent-session")
                check(sessionIndex >= 0)
                val original = altered[sessionIndex + 1]
                altered[sessionIndex + 1] = (if (original[0] == '0') "1" else "0") + original.substring(1)
                return RealProcessLauncher.startProcess(altered, redirectErrorStream).also { child = it }
            }
        }
        ProcessBroker(launcher = launcher).use { broker ->
            assertFailsWith<Exception> { broker.start() }
            assertFalse(checkNotNull(child).isAlive)
            assertEquals(0, broker.trackedWorkers())
            assertEquals(0, broker.provisionalWorkers())
        }
    }

    private fun filterCount(pid: Long): Int =
        Files
        .readAllLines(Path.of("/proc/$pid/status"))
        .single { it.startsWith("Seccomp_filters:") }
        .substringAfter(':')
        .trim()
        .toInt()
}
