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
    fun `timeout kills worker and later call succeeds`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 1, callTimeoutMs = 800).use { broker ->
            broker.start()
            assertThrows(PortalCallException::class.java) { broker.sleep(10_000) }
            assertEquals("after-timeout", broker.echo("after-timeout"))
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
}
