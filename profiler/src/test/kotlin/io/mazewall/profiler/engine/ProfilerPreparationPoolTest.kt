package io.mazewall.profiler.internal.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProfilerPreparationPoolTest {
    @Test
    @Timeout(10)
    fun `unreaped child fails pool without admitting replacement helpers`() {
        val reaping = CountDownLatch(1)
        val finishReap = CountDownLatch(1)
        val launches = java.util.concurrent.atomic
            .AtomicInteger()
        val pool = ProfilerPreparationPool(parallelism = 1, capacity = 1, launch = {
            launches.incrementAndGet()
            object : Process() {
                override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())

                override fun getOutputStream() = java.io.ByteArrayOutputStream()

                override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())

                override fun waitFor(): Int = error("Unbounded wait is forbidden")

                override fun waitFor(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean {
                    reaping.countDown()
                    check(finishReap.await(3, TimeUnit.SECONDS))
                    return false
                }

                override fun exitValue(): Int = throw IllegalThreadStateException("Child remains live")

                override fun destroy() = Unit

                override fun isAlive(): Boolean = true

                override fun toHandle(): ProcessHandle = NeverTerminates
            }
        })
        try {
            val notification = SeccompNotification(1, 7, 0, 39, LongArray(6))
            pool.submit(notification, "GETPID")
            assertTrue(reaping.await(3, TimeUnit.SECONDS))
            val queued = pool.submit(notification.copy(id = 2), "GETPID")
            finishReap.countDown()
            assertThrows(java.util.concurrent.ExecutionException::class.java) { queued.result.get(3, TimeUnit.SECONDS) }
            assertEquals(1, launches.get(), "An unreaped child must retain its helper slot")
            val rejected = pool.submit(notification.copy(id = 3), "GETPID")
            assertThrows(java.util.concurrent.ExecutionException::class.java) { rejected.result.get(3, TimeUnit.SECONDS) }
            assertEquals(1, launches.get())
        } finally {
            finishReap.countDown()
            assertThrows(java.io.IOException::class.java) { pool.close() }
        }
    }

    @Test
    @Timeout(15)
    fun `successful preparation reuses helper and stale closure cannot kill a later job`() {
        val launches = java.util.concurrent.atomic
            .AtomicInteger()
        val classpath = listOf(ProfilerPreparationMain::class.java, SeccompNotification::class.java, io.mazewall.enforcer.api.ContainmentViolationException::class.java, Unit::class.java)
            .map {
                java.io
                    .File(
                    it.protectionDomain.codeSource.location
                .toURI(),
                ).path
            }.distinct()
            .joinToString(java.io.File.pathSeparator)
        val command = io.mazewall.platform.process.JvmChildProcess.commandLine(
            io.mazewall.platform.process
                .JvmChildSpec(ProfilerPreparationMain::class.java.name, classpath = classpath, javaAgents = io.mazewall.platform.process.JavaAgentSelection.None),
        )
        ProfilerPreparationPool(parallelism = 1, capacity = 1, launch = {
            launches.incrementAndGet()
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        }).use { pool ->
            val first = pool.submit(SeccompNotification(1, 7, 0, 39, LongArray(6)), "GETPID")
            assertEquals("GETPID", first.result.get(5, TimeUnit.SECONDS).syscallName)
            val second = pool.submit(SeccompNotification(2, 7, 0, 39, LongArray(6)), "GETPID")
            first.close()
            first.close()
            assertEquals("GETPID", second.result.get(5, TimeUnit.SECONDS).syscallName)
            assertEquals(1, launches.get())
        }
    }

    @Test
    @Timeout(10)
    fun `cancelling stalled preparation kills and reaps its helper`() {
        val started = CountDownLatch(1)
        val child = AtomicReference<Process>()
        ProfilerPreparationPool(parallelism = 1, capacity = 1, launch = {
            ProcessBuilder("/bin/sleep", "30").start().also {
                child.set(it)
                started.countDown()
            }
        }).use { pool ->
            val pending = pool.submit(SeccompNotification(1, 7, 0, 2, LongArray(6)), "OPEN")
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertFalse(pending.result.isDone)
            pending.close()
            assertTrue(pending.result.isCancelled)
            assertTrue(child.get().waitFor(3, TimeUnit.SECONDS))
            assertFalse(child.get().isAlive)
        }
    }

    @Test
    @Timeout(10)
    fun `bounded preparation admission rejects overload and cancels queued work`() {
        val started = CountDownLatch(1)
        val launches = java.util.concurrent.atomic
            .AtomicInteger()
        ProfilerPreparationPool(parallelism = 1, capacity = 1, launch = {
            ProcessBuilder("/bin/sleep", "30").start().also {
                launches.incrementAndGet()
                started.countDown()
            }
        }).use { pool ->
            val notification = SeccompNotification(1, 7, 0, 2, LongArray(6))
            val active = pool.submit(notification, "OPEN")
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val queued = pool.submit(notification.copy(id = 2), "OPEN")
            val rejected = pool.submit(notification.copy(id = 3), "OPEN")
            assertTrue(rejected.result.isCompletedExceptionally)
            queued.close()
            active.close()
            assertTrue(queued.result.isCancelled)
            assertEquals(1, launches.get())
        }
    }
}

private object NeverTerminates : ProcessHandle {
    override fun pid(): Long = Long.MAX_VALUE

    override fun parent(): java.util.Optional<ProcessHandle> = java.util.Optional.empty()

    override fun children(): java.util.stream.Stream<ProcessHandle> =
        java.util.stream.Stream
        .empty()

    override fun descendants(): java.util.stream.Stream<ProcessHandle> =
        java.util.stream.Stream
        .empty()

    override fun info(): ProcessHandle.Info = error("No native process exists for this scripted fixture")

    override fun onExit(): java.util.concurrent.CompletableFuture<ProcessHandle> = java.util.concurrent.CompletableFuture()

    override fun supportsNormalTermination(): Boolean = false

    override fun destroy(): Boolean = false

    override fun destroyForcibly(): Boolean = false

    override fun isAlive(): Boolean = true

    override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
}
