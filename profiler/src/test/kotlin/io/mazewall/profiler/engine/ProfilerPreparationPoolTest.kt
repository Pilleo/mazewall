package io.mazewall.profiler.engine

import io.mazewall.platform.seccomp.SeccompNotification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProfilerPreparationPoolTest {
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
        val command = io.mazewall.core.JvmChildProcess.commandLine(
            io.mazewall.core.JvmChildSpec(ProfilerPreparationMain::class.java.name, classpath = classpath, javaAgents = io.mazewall.core.JavaAgentSelection.None),
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
