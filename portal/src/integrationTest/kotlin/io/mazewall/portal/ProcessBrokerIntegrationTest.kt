package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.Adler32

class ProcessBrokerIntegrationTest {
    @Test
    fun `granted-fd checksum works and worker cannot open host passwd`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        assumeTrue(java.nio.file.Files.exists(java.nio.file.Path.of("/etc/passwd")))
        val payload = Files.createTempFile("mazewall-portal-", ".bin")
        Files.write(payload, byteArrayOf(1, 2, 3, 4, 5))
        ProcessBroker().use { broker ->
            broker.start()
            assertEquals(1, broker.spawnedWorkers())
            val expected = Adler32().also { it.update(byteArrayOf(1, 2, 3, 4, 5)) }.value.toInt()
            val granted = broker.openReadOnly(payload.parent, payload.fileName.toString())
            assertEquals(expected, broker.checksum(granted))
            val ex = assertThrows(PortalCallException::class.java) { broker.tryOpenHostPasswd() }
            val msg = ex.message ?: ""
            val strerror13 = io.mazewall.ffi.memory.getSystemStrerror(13)
            val matchesLocale = strerror13 != null && msg.contains(strerror13, ignoreCase = true)
            assertTrue(
                msg.contains("Permission denied", ignoreCase = true) ||
                    msg.contains("EACCES") ||
                    msg.contains("EPERM") ||
                    matchesLocale,
                "expected Landlock denial, got: $msg",
            )
            val afterDeny = broker.spawnedWorkers()
            assertEquals("after-landlock-deny", broker.echo("after-landlock-deny"))
            assertEquals(afterDeny, broker.spawnedWorkers(), "ERROR must not recycle the worker JVM")
        }
        Files.deleteIfExists(payload)
    }

    @Test
    fun `echo and granted-fd checksum round trip`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val payload = Files.createTempFile("mazewall-portal-", ".bin")
        Files.write(payload, byteArrayOf(1, 2, 3, 4, 5))
        ProcessBroker().use { broker ->
            broker.start()
            assertEquals("hello-portal", broker.echo("hello-portal"))
            val expected = Adler32().also { it.update(byteArrayOf(1, 2, 3, 4, 5)) }.value.toInt()
            val granted = broker.openReadOnly(payload.parent, payload.fileName.toString())
            assertEquals(expected, broker.checksum(granted))
        }
        Files.deleteIfExists(payload)
    }

    @Test
    fun `pool of two serves concurrent echoes`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 2).use { broker ->
            broker.start()
            val a = CompletableFuture.supplyAsync { broker.echo("alpha") }
            val b = CompletableFuture.supplyAsync { broker.echo("beta") }
            assertEquals("alpha", a.get(20, TimeUnit.SECONDS))
            assertEquals("beta", b.get(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `one worker serves independent requests concurrently`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 1, callTimeoutMs = 5_000).use { broker ->
            broker.start()
            val startedAt = System.nanoTime()
            val a = CompletableFuture.runAsync { broker.sleep(400) }
            val b = CompletableFuture.runAsync { broker.sleep(400) }
            a.get(5, TimeUnit.SECONDS)
            b.get(5, TimeUnit.SECONDS)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(elapsedMs < 700, "one concurrent worker should not serialize calls: ${elapsedMs}ms")
        }
    }

    @Test
    fun `timeout kills worker and later call succeeds`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 1, callTimeoutMs = 800).use { broker ->
            broker.start()
            assertThrows(PortalCallException::class.java) { broker.sleep(10_000) }
            assertEquals("after-timeout", broker.echo("after-timeout"))
        }
    }

    @Test
    fun `one timeout fails every request on its worker and creates one replacement`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 1, callTimeoutMs = 500).use { broker ->
            broker.start()
            val a = CompletableFuture.supplyAsync { runCatching { broker.sleep(10_000) } }
            val b = CompletableFuture.supplyAsync { runCatching { broker.sleep(10_000) } }
            assertTrue(a.get(5, TimeUnit.SECONDS).isFailure)
            assertTrue(b.get(5, TimeUnit.SECONDS).isFailure)
            assertEquals(2, broker.spawnedWorkers(), "one failed connection gets one replacement")
            assertEquals("after-shared-timeout", broker.echo("after-shared-timeout"))
        }
    }

    @Test
    fun `crashed worker is replaced and later call succeeds`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 1).use { broker ->
            broker.start()
            broker.crashIdleWorkerProcess()
            assertThrows(PortalCallException::class.java) { broker.echo("fail-inflight") }
            assertEquals("after-crash", broker.echo("after-crash"))
        }
    }

    @Test
    fun `close destroys checked-out workers instead of orphaning them`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val broker = ProcessBroker()
        broker.start()
        try {
            // Check out the only worker via a long sleep on a separate thread.
            val inFlight = CompletableFuture.runAsync { broker.sleep(60_000) }
            // A multiplexed connection remains admissible while requests are in
            // flight; close must still destroy that live worker.
            Thread.sleep(100)
            assertEquals(1, broker.trackedWorkers(), "in-flight worker must remain tracked")

            broker.close()

            // In-flight caller must fail fast instead of hanging on a destroyed channel.
            inFlight.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Accepted outcome: call aborted by close.
        }

        // The decisive assertion: no portal worker JVM survives its broker.
        val deadline = System.currentTimeMillis() + 5_000
        var survivors: List<ProcessHandle>
        do {
            survivors = ProcessHandle.current().descendants()
                .filter { h ->
                    h.info().commandLine().orElse("").contains("io.mazewall.portal.worker.PortalWorkerMain")
                }
                .toList()
        } while (survivors.isNotEmpty() && System.currentTimeMillis() < deadline)

        assertEquals(0, survivors.size, "checked-out workers must be destroyed on close: $survivors")
    }

    @Test
    fun `recycled worker is replaced without serializing boot and teardown`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 2).use { broker ->
            broker.start()
            assertEquals(2, broker.trackedWorkers())
            broker.crashIdleWorkerProcess()

            // Pre-spawn overlap: replacement must already exist right after the crash hook.
            assertEquals(2, broker.trackedWorkers(), "replacement should be spawned before corpse teardown")

            assertEquals("still-alive", broker.echo("still-alive"))
        }
    }



    @Test
    fun `worker survives multiple idle ticks and still serves calls`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        // Regression for the pooled-worker self-exit bug (011654): a 500ms receive deadline
        // fires ~3 times during this sleep; the old break-on-timeout loop killed the worker.
        val broker = ProcessBroker(
            workerExtraJvmArgs = listOf("-Dio.mazewall.portal.worker.idleTimeoutMs=500"),
        )
        broker.use { b ->
            b.start()
            Thread.sleep(1_500)
            assertEquals("after-idle", b.echo("after-idle"))
            assertEquals(1, b.spawnedWorkers(), "idle ticks must not respawn workers")
        }
    }
}
