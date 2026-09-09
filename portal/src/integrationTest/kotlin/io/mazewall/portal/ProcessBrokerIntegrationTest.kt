package io.mazewall.portal

import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Adler32

@Execution(ExecutionMode.SAME_THREAD)
class ProcessBrokerIntegrationTest {
    @Test
    fun `close waits for cancelled startup and reaps every provisional child`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val secondLaunchEntered = CountDownLatch(1)
        val releaseSecondLaunch = CountDownLatch(1)
        val children = java.util.concurrent.CopyOnWriteArrayList<Process>()
        val starts = AtomicInteger()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                if (starts.incrementAndGet() == 2) {
                    secondLaunchEntered.countDown()
                    assertTrue(releaseSecondLaunch.await(5, TimeUnit.SECONDS))
                }
                return RealProcessLauncher.startProcess(args, redirectErrorStream).also(children::add)
            }
        }
        val broker = ProcessBroker(poolSize = 2, launcher = launcher)
        val startup = CompletableFuture.runAsync { broker.start() }
        assertTrue(secondLaunchEntered.await(10, TimeUnit.SECONDS))

        val closing = CompletableFuture.runAsync { broker.close() }
        Thread.sleep(100)
        assertFalse(closing.isDone, "close must join the startup owner before publishing Closed")
        releaseSecondLaunch.countDown()

        assertThrows(Exception::class.java) { startup.get(10, TimeUnit.SECONDS) }
        closing.get(10, TimeUnit.SECONDS)
        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
        assertTrue(children.none(Process::isAlive), "all provisional children must be reaped")
    }

    @Test
    fun `failed provisional rollback is retained for close retry`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val starts = AtomicInteger()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                if (starts.incrementAndGet() == 2) error("second worker launch failed")
                return RealProcessLauncher.startProcess(args, redirectErrorStream)
            }
        }
        val closes = AtomicInteger()
        val sockets = object : io.mazewall.core.SocketManager by io.mazewall.core.RealSocketManager {
            override fun close(fd: io.mazewall.core.FileDescriptor<*, io.mazewall.core.FdState.Open, io.mazewall.core.FdOwnership.Owned>) {
                if (closes.incrementAndGet() == 1) throw java.io.IOException("pre-native channel close")
                io.mazewall.core.RealSocketManager
                    .close(fd)
            }
        }
        val broker = ProcessBroker(poolSize = 2, launcher = launcher, sockets = sockets)

        assertThrows(IllegalStateException::class.java) { broker.start() }
        assertEquals(1, broker.provisionalWorkers(), "unfinished rollback owner must remain registered")

        broker.close()

        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
        assertEquals(0, broker.provisionalWorkers())
        assertEquals(4, closes.get(), "retry only the provisional listener whose pre-native close failed")
    }

    @Test
    fun `second worker startup failure rolls back the unpublished first worker`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val starts = AtomicInteger()
        var first: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                if (starts.incrementAndGet() == 2) error("second worker launch failed")
                return RealProcessLauncher.startProcess(args, redirectErrorStream).also { first = it }
            }
        }
        val broker = ProcessBroker(poolSize = 2, launcher = launcher)

        assertThrows(IllegalStateException::class.java) { broker.start() }

        assertEquals(ProcessBrokerLifecycle.Failed("second worker launch failed"), broker.lifecycleState())
        assertEquals(0, broker.trackedWorkers())
        assertEquals(0, broker.idleSize())
        assertTrue(first?.isAlive == false, "provisional first worker must be destroyed and reaped")
        broker.close()
    }

    @Test
    fun `worker connection and readiness share one bounded startup deadline`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        var child: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process = ProcessBuilder("sh", "-c", "sleep 60").start().also { child = it }
        }
        val broker = ProcessBroker(launcher = launcher, startupTimeoutMs = 150)
        val startedAt = System.nanoTime()

        assertThrows(IllegalStateException::class.java) { broker.start() }

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue(elapsedMs < 3_000, "startup deadline escaped: ${elapsedMs}ms")
        assertTrue(child?.isAlive == false, "timed-out worker must be destroyed and reaped")
        assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
        broker.close()
    }

    @Test
    fun `worker checkout consumes the same budget as the subsequent RPC`() {
        ProcessBroker(callTimeoutMs = 2_000).use { broker ->
            broker.start()
            val first = CompletableFuture.runAsync { broker.sleep(1_500) }
            io.mazewall.testing.awaitCondition(java.time.Duration.ofSeconds(2), "first RPC checked out the worker") { broker.idleSize() == 0 }
            assertThrows(PortalCallException::class.java) { broker.sleep(1_500) }
            first.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun `granted-fd checksum works and worker cannot open host passwd`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        assumeTrue(
            java.nio.file.Files
            .exists(
                java.nio.file.Path
                .of("/etc/passwd"),
            ),
        )
        val payload = Files.createTempFile("mazewall-portal-", ".bin")
        Files.write(payload, byteArrayOf(1, 2, 3, 4, 5))
        ProcessBroker().use { broker ->
            broker.start()
            assertEquals(1, broker.spawnedWorkers())
            val expected = Adler32().also { it.update(byteArrayOf(1, 2, 3, 4, 5)) }.value.toInt()
            broker.openReadOnly(payload.parent, payload.fileName.toString()).use { granted ->
                assertEquals(expected, broker.checksum(granted))
            }
            val ex = assertThrows(PortalCallException::class.java) { broker.tryOpenHostPasswd() }
            val msg = ex.message ?: ""
            val strerror13 = io.mazewall.ffi.memory
                .getSystemStrerror(13)
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
            broker.openReadOnly(payload.parent, payload.fileName.toString()).use { granted ->
                assertEquals(expected, broker.checksum(granted))
            }
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
            awaitCondition(java.time.Duration.ofSeconds(10), "timed-out worker replacement") { broker.idleSize() == 1 }
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
            awaitCondition(java.time.Duration.ofSeconds(10), "crashed worker replacement") { broker.idleSize() == 1 }
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
            // Wait until the slot is actually checked out (idle queue empty).
            awaitCondition(description = "portal caller checked out the sole worker") {
                broker.idleSize() == 0
            }
            assertEquals(0, broker.idleSize(), "worker should be checked out by now")

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
            survivors = ProcessHandle
                .current()
                .descendants()
                .filter { h ->
                    h
                        .info()
                        .commandLine()
                        .orElse("")
                        .contains("io.mazewall.portal.worker.PortalWorkerMain")
                }.toList()
        } while (survivors.isNotEmpty() && System.currentTimeMillis() < deadline)

        assertEquals(0, survivors.size, "checked-out workers must be destroyed on close: $survivors")
    }

    @Test
    fun `recycled worker is retired before asynchronous replacement`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(poolSize = 2).use { broker ->
            broker.start()
            assertEquals(2, broker.trackedWorkers())
            broker.crashIdleWorkerProcess()

            assertEquals("still-alive", broker.echo("still-alive"))
            assertThrows(PortalCallException::class.java) { broker.echo("dead-worker") }
            awaitCondition(java.time.Duration.ofSeconds(10), "replacement after corpse retirement") {
                broker.trackedWorkers() == 2 && broker.idleSize() == 2
            }
            assertEquals(3, broker.spawnedWorkers(), "one worker should replace the reaped corpse")
        }
    }

    @Test
    fun `worker survives multiple idle ticks and still serves calls`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        // Regression for the pooled-worker self-exit bug (011654): a 500ms receive deadline
        // fires twice during this sleep. Keep the request away from the exact deadline edge:
        // once a header byte arrives, expiry is correctly a terminal partial-frame timeout.
        val broker = ProcessBroker(
            workerExtraJvmArgs = listOf("-Dio.mazewall.portal.worker.idleTimeoutMs=500"),
        )
        broker.use { b ->
            b.start()
            Thread.sleep(1_250) // Workload delay: keep the worker idle across multiple real receive-timeout intervals.
            assertEquals("after-idle", b.echo("after-idle"))
            assertEquals(1, b.spawnedWorkers(), "idle ticks must not respawn workers")
        }
    }
}
