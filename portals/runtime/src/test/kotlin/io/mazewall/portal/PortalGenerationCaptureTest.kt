package io.mazewall.portal

import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PortalGenerationCaptureTest {
    @Test
    fun `completed unobserved fatal is retained when cancellation wins result delivery`() {
        val captures = PortalGenerationCaptures()
        val active = java.util.concurrent.atomic
            .AtomicBoolean(true)
        val source = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        val fatal = AssertionError("completed but not delivered")
        val authority = {
            if (active.get()) {
                true
            } else {
                source.get().join(1000) // Deterministically make the physical thread already ended.
                false
            }
        }
        assertThrows(IllegalStateException::class.java) {
            captures.measure(Deadline.afterMillis(2000), authority) {
                source.set(Thread.currentThread())
                active.set(false)
                throw fatal
            }
        }
        assertEquals(1, captures.retainedCount)
        assertFalse(source.get().isAlive)
        Thread.currentThread().interrupt()
        try {
            assertSame(fatal, assertThrows(AssertionError::class.java) { captures.retire(Deadline.afterMillis(1000)) })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, captures.retainedCount)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `pre interrupted retirement retains physically pending owner`() = interruptedRetirement(true)

    @Test
    fun `interrupt during retirement retains physically pending owner`() = interruptedRetirement(false)

    private fun interruptedRetirement(beforeWait: Boolean) {
        val captures = PortalGenerationCaptures()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        val startup = CompletableFuture.supplyAsync {
            runCatching {
                captures.measure(Deadline.afterMillis(100), { true }) {
                    source.set(Thread.currentThread())
                    entered.countDown()
                    while (true) {
                        try {
                            release.await()
                            break
                        } catch (_: InterruptedException) {
                            // Real physical work remains owned until the gate is released.
                        }
                    }
                }
            }.exceptionOrNull()
        }
        val outcome = CompletableFuture<Pair<Result<Boolean>, Boolean>>()
        val retiring = Thread.ofPlatform().unstarted {
            if (beforeWait) Thread.currentThread().interrupt()
            val result = runCatching { captures.retire(Deadline.afterMillis(2000)) }
            outcome.complete(result to Thread.currentThread().isInterrupted)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertInstanceOf(IllegalStateException::class.java, startup.get(2, TimeUnit.SECONDS))
            assertEquals(1, captures.retainedCount)
            assertTrue(source.get().isAlive)
            retiring.start()
            if (!beforeWait) {
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (retiring.stackTrace.none { it.methodName.startsWith("awaitPhysicalEnd") } &&
                    retiring.isAlive &&
                    System.nanoTime() < limit
                ) {
                        Thread.yield()
                    }
                assertTrue(retiring.stackTrace.any { it.methodName.startsWith("awaitPhysicalEnd") }, retiring.stackTrace.contentToString())
                retiring.interrupt()
            }
            val (result, interrupted) = outcome.get(1, TimeUnit.SECONDS)
            assertEquals(false, result.getOrThrow(), "interrupted wait must report incomplete retirement")
            assertTrue(interrupted, "retirement must restore interruption")
            assertEquals(1, captures.retainedCount)
            assertTrue(source.get().isAlive)
        } finally {
            release.countDown()
            retiring.join(2000)
            source.get()?.join(2000)
            assertFalse(retiring.isAlive)
            assertFalse(source.get()?.isAlive ?: false)
            assertTrue(captures.retire(Deadline.afterMillis(1000)))
            assertEquals(0, captures.retainedCount)
        }
    }

    @Test
    fun `blocking interrupt channel close callback cannot stall startup deadline or fabricate physical end`() {
        val captures = PortalGenerationCaptures()
        val reading = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val channel = object : java.nio.channels.spi.AbstractInterruptibleChannel() {
            fun read() {
                begin()
                try {
                    reading.countDown()
                    var released = false
                    while (!released) {
                        try {
                            releaseRead.await()
                            released = true
                        } catch (_: InterruptedException) {
                            // physical read remains
                        }
                    }
                } finally {
                    end(false)
                }
            }

            override fun implCloseChannel() {
                closing.countDown()
                releaseClose.await() // FileChannel native close can likewise wait on native IO.
            }
        }
        val startup = CompletableFuture.supplyAsync { runCatching { captures.measure(Deadline.afterMillis(100), { true }) { channel.read() } }.exceptionOrNull() }
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertInstanceOf(IllegalStateException::class.java, startup.get(500, TimeUnit.MILLISECONDS), "interrupt callback is also physically owned blocking work")
            assertEquals(1, captures.retainedCount)
            assertFalse(captures.retire(Deadline.afterMillis(10)))
        } finally {
            releaseRead.countDown()
            releaseClose.countDown()
            startup.get(2, TimeUnit.SECONDS)
            assertTrue(captures.retire(Deadline.afterMillis(1000)))
        }
    }

    @Test
    fun `late fatal capture outcome remains observable at physical owner retirement`() {
        val captures = PortalGenerationCaptures()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fatal = AssertionError("late physical IO failure")
        val result = CompletableFuture.supplyAsync {
            runCatching {
                captures.measure(Deadline.afterMillis(100), { true }) {
                entered.countDown()
                var released = false
                while (!released) {
                    try {
                        release.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // physical owner still executing
                    }
                }
                throw fatal
            }
            }.exceptionOrNull()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertInstanceOf(IllegalStateException::class.java, result.get(2, TimeUnit.SECONDS))
            assertEquals(1, captures.retainedCount)
            release.countDown()
            assertSame(fatal, assertThrows(AssertionError::class.java) { captures.retire(Deadline.afterMillis(1000)) })
            assertEquals(0, captures.retainedCount)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `actual hashing and directory enumeration consult cancellation authority during work`() {
        val file = java.nio.file.Files
            .write(directory.resolve("input.bin"), ByteArray(1024 * 1024))
        val hashChecks = AtomicInteger()
        val hashBudget = PortalMeasurementBudget(active = { hashChecks.incrementAndGet() < 10 })
        assertThrows(IllegalStateException::class.java) { PortalWorkerGeneration.capture(file.toString(), null, emptyList(), budget = hashBudget) }
        assertEquals(10, hashChecks.get(), "actual digest chunks must observe cancellation")
        val classes = java.nio.file.Files
            .createDirectory(directory.resolve("classes"))
        repeat(100) {
            java.nio.file.Files
            .writeString(classes.resolve("$it.class"), "content")
        }
        val enumerationChecks = AtomicInteger()
        val enumerationBudget = PortalMeasurementBudget(active = { enumerationChecks.incrementAndGet() < 10 })
        assertThrows(IllegalStateException::class.java) { PortalWorkerGeneration.capture(classes.toString(), null, emptyList(), budget = enumerationBudget) }
        assertEquals(10, enumerationChecks.get(), "directory enumeration must observe cancellation before hashing")
    }

    @TempDir lateinit var directory: Path

    @Test
    fun `expiry during blocking capture returns promptly retains physical IO owner and never launches late child`() = blockedCapture(false)

    @Test
    fun `close during blocking capture cancels startup retains physical IO owner and never launches late child`() = blockedCapture(true)

    private fun blockedCapture(cancel: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launches = AtomicInteger()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                launches.incrementAndGet()
                error("inactive capture must not launch")
            }
        }
        val launch = PortalWorkerLaunch(
            emptyList(),
            PortalWorkerArtifact(directory.resolve("unused.zip"), "0".repeat(64)),
            PortalGenerationMeasurement { _, _, _ ->
            entered.countDown()
            // Models a metadata/open operation which does not respond to interruption. Result
            // delivery must time out/cancel, but physical ownership must remain until release.
            var released = false
            while (!released) {
                try {
                    release.await()
                    released = true
                } catch (_: InterruptedException) {
                    // still physically blocked
                }
            }
            PortalWorkerGeneration("a".repeat(64), "b".repeat(64), "c".repeat(64))
        },
        )
        val broker = ProcessBroker(
            launcher = launcher,
            workerClasspath = directory.toString(),
            workerLaunch = launch,
            limits = PortalRuntimeLimits(startupTimeoutMs = if (cancel) 5000 else 250, closeTimeoutMs = 100),
        )
        try {
            val startup = CompletableFuture.supplyAsync { runCatching { broker.start() }.exceptionOrNull() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            if (cancel) assertThrows(Exception::class.java) { broker.close() }
            val failure = startup.get(2, TimeUnit.SECONDS)
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains(if (cancel) "cancelled" else "deadline"), failure.toString())
            assertEquals(0, launches.get())
            assertThrows(Exception::class.java) { broker.close() }
            release.countDown()
            broker.close()
            assertEquals(0, launches.get(), "late measurement completion must have no launch effect")
        } finally {
            release.countDown()
            broker.close()
        }
    }

    @Test
    fun `expired or inactive capture never enters measurement IO`() {
        val captures = PortalGenerationCaptures()
        val expired = Deadline.afterMillis(1)
        Thread.sleep(5)
        assertThrows(IllegalStateException::class.java) { captures.measure(expired, { true }) { error("IO entered after deadline") } }
        assertThrows(IllegalStateException::class.java) { captures.measure(Deadline.afterMillis(1000), { false }) { error("IO entered after cancellation") } }
        assertEquals(0, captures.retainedCount)
    }

    @Test
    fun `classpath enumeration and measured bytes have explicit bounds`() {
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerGeneration.readManifests("x".repeat(PortalMeasurementBudget.MAX_CLASSPATH_CHARS + 1)) }
        val deep = java.nio.file.Files
            .createDirectory(directory.resolve("deep"))
        java.nio.file.Files
            .createDirectories(deep.resolve("d/".repeat(PortalMeasurementBudget.MAX_DEPTH)))
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerGeneration.capture(deep.toString(), null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerGeneration.readManifests(List(257) { directory.toString() }.joinToString(java.io.File.pathSeparator)) }
        val enumeration = PortalMeasurementBudget()
        assertThrows(IllegalArgumentException::class.java) { repeat(PortalMeasurementBudget.MAX_ENTRIES + 1) { enumeration.entry() } }
        val bytes = PortalMeasurementBudget()
        val input = object : java.io.InputStream() {
            override fun read(): Int = 0

            override fun read(buffer: ByteArray): Int = buffer.size
        }
        val buffer = ByteArray(8192)
        assertThrows(IllegalArgumentException::class.java) { repeat((PortalMeasurementBudget.MAX_BYTES / buffer.size + 1).toInt()) { bytes.read(input, buffer) } }
    }
}
