package io.mazewall.portal

import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
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
    private companion object {
        val FIXTURE_ROOT = PortalGrantRootId("fixture")
    }

    @Test
    fun `fatal checkout retires child before propagating original error and suppresses cleanup`() {
        val children = java.util.concurrent.CopyOnWriteArrayList<Process>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process = RealProcessLauncher.startProcess(args, redirectErrorStream).also(children::add)
        }
        val cleanup = java.io.IOException("listener cleanup failed")
        val failClose = java.util.concurrent.atomic
            .AtomicBoolean(false)
        val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (failClose.compareAndSet(true, false)) throw cleanup
                io.mazewall.platform.socket.RealSocketManager
                    .close(fd)
            }
        }
        val broker = ProcessBroker(launcher = launcher, sockets = sockets)
        broker.start()
        val child = children.single()
        val runtime = io.mazewall.platform.native.LinuxNative
            .snapshot()
        val fatal = AssertionError("fatal request write")
        io.mazewall.platform.native.LinuxNative.setEngine(object : io.mazewall.platform.native.NativeEngine by runtime {
            override val memory = object : io.mazewall.platform.native.NativeMemory by runtime.memory {
                override fun write(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    buf: io.mazewall.ffi.memory.ManagedSegment,
                    count: Long,
                ): io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> = throw fatal
            }
        })
        try {
            failClose.set(true)
            assertSame(fatal, assertThrows(AssertionError::class.java) { broker.echo("fail") })
            assertFalse(child.isAlive, "fatal return must not leave checked-out process alive")
            assertTrue(fatal.suppressed.contains(cleanup), "channel cleanup must preserve fatal identity")
        } finally {
            io.mazewall.platform.native.LinuxNative
                .resetToDefault()
            broker.close()
        }
    }

    @Test
    fun `join timeout retains late process publication until it is reaped`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val launchEntered = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        var lateChild: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                launchEntered.countDown()
                assertTrue(releaseLaunch.await(5, TimeUnit.SECONDS))
                return RealProcessLauncher.startProcess(args, redirectErrorStream).also { lateChild = it }
            }
        }
        val broker = ProcessBroker(launcher = launcher, startupTimeoutMs = 100)
        val startup = CompletableFuture.runAsync { broker.start() }
        assertTrue(launchEntered.await(5, TimeUnit.SECONDS))

        assertThrows(IncompleteBrokerCleanupException::class.java) { broker.close() }

        assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
        assertEquals(1, broker.provisionalWorkers(), "active acquisition must remain registered after join timeout")
        releaseLaunch.countDown()
        assertThrows(Exception::class.java) { startup.get(10, TimeUnit.SECONDS) }
        awaitCondition(java.time.Duration.ofSeconds(5), "late child reaped") { lateChild?.isAlive == false }
        broker.close()
        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
        assertEquals(0, broker.provisionalWorkers())
    }

    @Test
    fun `interrupted close retains active acquisition for later cleanup`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val launchEntered = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        var lateChild: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                launchEntered.countDown()
                assertTrue(releaseLaunch.await(5, TimeUnit.SECONDS))
                return RealProcessLauncher.startProcess(args, redirectErrorStream).also { lateChild = it }
            }
        }
        val broker = ProcessBroker(launcher = launcher, startupTimeoutMs = 5_000)
        val startup = CompletableFuture.runAsync { broker.start() }
        assertTrue(launchEntered.await(5, TimeUnit.SECONDS))
        val closeReturned = CountDownLatch(1)
        val closeFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val closer = Thread.ofPlatform().start {
            try {
                broker.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            } finally {
                closeReturned.countDown()
            }
        }
        awaitCondition(java.time.Duration.ofSeconds(2), "close waiting for startup owner") {
            closer.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED)
        }
        closer.interrupt()
        assertTrue(closeReturned.await(2, TimeUnit.SECONDS))
        assertTrue(closeFailure.get() is IncompleteBrokerCleanupException)

        assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
        assertEquals(1, broker.provisionalWorkers())
        releaseLaunch.countDown()
        assertThrows(Exception::class.java) { startup.get(10, TimeUnit.SECONDS) }
        awaitCondition(java.time.Duration.ofSeconds(5), "late interrupted-close child reaped") { lateChild?.isAlive == false }
        broker.close()
        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
    }

    @Test
    fun `fatal close still publishes failure and releases concurrent close waiter`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val fatal = AssertionError("fatal pre-native close")
        val attempts = AtomicInteger()
        val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (attempts.incrementAndGet() == 1) {
                    closeEntered.countDown()
                    assertTrue(releaseClose.await(5, TimeUnit.SECONDS))
                    throw fatal
                }
                io.mazewall.platform.socket.RealSocketManager
                    .close(fd)
            }
        }
        val broker = ProcessBroker(sockets = sockets)
        broker.start()
        val first = CompletableFuture.runAsync { broker.close() }
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
        val waiter = CompletableFuture.runAsync { broker.close() }
        releaseClose.countDown()

        val thrown = assertThrows(Exception::class.java) { first.get(10, TimeUnit.SECONDS) }
        assertSame(fatal, thrown.cause)
        val waiterThrown = assertThrows(Exception::class.java) { waiter.get(5, TimeUnit.SECONDS) }
        assertSame(fatal, waiterThrown.cause, "every closer must observe the shared cleanup failure")
        assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)

        broker.close()
        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
    }

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
        awaitCondition(java.time.Duration.ofSeconds(2), "broker entered closing lifecycle") {
            broker.lifecycleState() == ProcessBrokerLifecycle.Closing
        }
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
        val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (closes.incrementAndGet() == 1) throw java.io.IOException("pre-native channel close")
                io.mazewall.platform.socket.RealSocketManager
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
    fun `fatal provisional rollback remains retryable after startup publishes failure`() {
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
        val fatal = AssertionError("fatal provisional listener close")
        val closes = AtomicInteger()
        val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (closes.incrementAndGet() == 1) throw fatal
                io.mazewall.platform.socket.RealSocketManager
                    .close(fd)
            }
        }
        val broker = ProcessBroker(poolSize = 2, launcher = launcher, sockets = sockets)

        assertSame(fatal, assertThrows(AssertionError::class.java) { broker.start() })
        assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
        assertEquals(1, broker.provisionalWorkers())

        broker.close()

        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
        assertEquals(0, broker.provisionalWorkers())
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
    fun `interrupted capability admission preserves interruption and never reuses the partial-frame worker`() {
        val fixture = Files.createTempFile("mazewall-portal-interrupt-", ".txt")
        Files.writeString(fixture, "blocked grant")
        ProcessBroker(callTimeoutMs = 5_000, grantRoots = PortalGrantRoots.of(PortalGrantRoot(FIXTURE_ROOT, fixture.parent))).use { broker ->
            broker.start()
            val capability = broker.openReadOnly(FIXTURE_ROOT, fixture.fileName.toString())
            val borrowEntered = CountDownLatch(1)
            val releaseBorrow = CountDownLatch(1)
            val borrower = CompletableFuture.runAsync {
                capability.borrow {
                    borrowEntered.countDown()
                    assertTrue(releaseBorrow.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(borrowEntered.await(2, TimeUnit.SECONDS))
            val interrupted = java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
            val interruptPreserved = java.util.concurrent.atomic
                .AtomicBoolean()
            val waiting = Thread.ofPlatform().start {
                try {
                    broker.checksum(capability)
                } catch (failure: Throwable) {
                    interrupted.set(failure)
                    interruptPreserved.set(Thread.currentThread().isInterrupted)
                }
            }
            awaitCondition(java.time.Duration.ofSeconds(2), "caller admitted channel before blocked capability") {
                broker.idleSize() == 0 && waiting.state == Thread.State.TIMED_WAITING
            }
            waiting.interrupt()
            waiting.join(2_000)
            releaseBorrow.countDown()
            borrower.get(2, TimeUnit.SECONDS)
            capability.close()
            Files.deleteIfExists(fixture)

            assertFalse(waiting.isAlive)
            assertTrue(interruptPreserved.get(), "broker must restore the interrupted flag")
            val failure = interrupted.get()
            assertTrue(failure is PortalCallException)
            assertTrue(failure?.cause is InterruptedException)
            awaitCondition(java.time.Duration.ofSeconds(10), "interrupted partial-frame worker replaced") {
                broker.idleSize() == 1 && broker.spawnedWorkers() == 2
            }
            assertEquals("still-usable", broker.echo("still-usable"))
        }
    }

    @Test
    fun `interrupted idle checkout preserves interruption without retiring an unclaimed worker`() {
        ProcessBroker(callTimeoutMs = 5_000).use { broker ->
            broker.start()
            val active = CompletableFuture.runAsync { broker.sleep(2_000) }
            awaitCondition(java.time.Duration.ofSeconds(2), "worker checked out") { broker.idleSize() == 0 }
            val interrupted = java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
            val interruptPreserved = java.util.concurrent.atomic
                .AtomicBoolean()
            val waiting = Thread.ofPlatform().start {
                try {
                    broker.echo("must-not-run")
                } catch (failure: Throwable) {
                    interrupted.set(failure)
                    interruptPreserved.set(Thread.currentThread().isInterrupted)
                }
            }
            awaitCondition(java.time.Duration.ofSeconds(2), "second caller waiting for worker") {
                waiting.state == Thread.State.TIMED_WAITING || waiting.state == Thread.State.WAITING
            }
            waiting.interrupt()
            waiting.join(2_000)

            assertFalse(waiting.isAlive)
            assertTrue(interruptPreserved.get(), "broker must restore the interrupted flag")
            val failure = interrupted.get()
            assertTrue(failure is PortalCallException)
            assertTrue(failure?.cause is InterruptedException)
            active.get(5, TimeUnit.SECONDS)
            assertEquals(1, broker.spawnedWorkers())
            assertEquals("still-usable", broker.echo("still-usable"))
        }
    }

    @Test
    fun `granted-fd checksum works through a startup-authorized root`() {
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
        ProcessBroker(grantRoots = PortalGrantRoots.of(PortalGrantRoot(FIXTURE_ROOT, payload.parent))).use { broker ->
            broker.start()
            assertEquals(1, broker.spawnedWorkers())
            val expected = Adler32().also { it.update(byteArrayOf(1, 2, 3, 4, 5)) }.value.toInt()
            broker.openReadOnly(FIXTURE_ROOT, payload.fileName.toString()).use { granted ->
                assertEquals(expected, broker.checksum(granted))
            }
            assertEquals("after-landlock-deny", broker.echo("after-landlock-deny"))
        }
        Files.deleteIfExists(payload)
    }

    @Test
    fun `echo and granted-fd checksum round trip`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val payload = Files.createTempFile("mazewall-portal-", ".bin")
        Files.write(payload, byteArrayOf(1, 2, 3, 4, 5))
        ProcessBroker(grantRoots = PortalGrantRoots.of(PortalGrantRoot(FIXTURE_ROOT, payload.parent))).use { broker ->
            broker.start()
            assertEquals("hello-portal", broker.echo("hello-portal"))
            val expected = Adler32().also { it.update(byteArrayOf(1, 2, 3, 4, 5)) }.value.toInt()
            broker.openReadOnly(FIXTURE_ROOT, payload.fileName.toString()).use { granted ->
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
