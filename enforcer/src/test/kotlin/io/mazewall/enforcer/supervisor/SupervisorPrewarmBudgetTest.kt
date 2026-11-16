package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockProcess
import io.mazewall.enforcer.api.MockProcessLauncher
import io.mazewall.enforcer.api.MockSocketManager
import io.mazewall.enforcer.internal.supervisor.SocketHelper
import io.mazewall.enforcer.internal.supervisor.SocketHelperPool
import io.mazewall.enforcer.internal.supervisor.SocketHelperResult
import io.mazewall.enforcer.internal.supervisor.SocketOperation
import io.mazewall.enforcer.internal.supervisor.SupervisorDaemon
import io.mazewall.enforcer.internal.supervisor.SupervisorDaemonEngine
import io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.enforcer.internal.supervisor.SupervisorPrewarmBudget
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SupervisorPrewarmBudgetTest {
    @Test
    fun `policy accepts only bounded positive ASCII decimal and preserves default`() {
        assertEquals(5000L, SupervisorPrewarmBudget.parse(null))
        assertEquals(1L, SupervisorPrewarmBudget.parse("1"))
        assertEquals(15000L, SupervisorPrewarmBudget.parse("15000"))
        for (invalid in listOf("", "0", "15001", "-1", "+1", " 1", "1 ", "1.0", "١", "999999999999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { SupervisorPrewarmBudget.parse(invalid) }
        }
        assertEquals(emptyList<String>(), SupervisorPrewarmBudget.childArguments(null))
        assertEquals(listOf("-Dmazewall.supervisor.prewarmTimeoutMillis=15000"), SupervisorPrewarmBudget.childArguments("015000"))
    }

    @Test
    fun `manager forwards only canonical explicit policy to supervisor child`() {
        val property = SupervisorPrewarmBudget.PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "015000")
            val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
            val manager = SupervisorDaemonManager(MockNativeEngine(), MockSocketManager(), launcher)
            try {
                manager.getOrSpawnSharedDaemon()
                val args = requireNotNull(launcher.lastArgs)
                assertEquals(1, args.count { it.startsWith("-D$property=") })
                assertTrue(args.contains("-D$property=15000"))
            } finally {
                manager.stop()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `invalid policy prevents supervisor process launch`() {
        val property = SupervisorPrewarmBudget.PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "15001")
            val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
            val manager = SupervisorDaemonManager(MockNativeEngine(), MockSocketManager(), launcher)
            try {
                assertThrows(IllegalArgumentException::class.java) { manager.getOrSpawnSharedDaemon() }
                assertEquals(null, launcher.lastArgs)
            } finally {
                manager.stop()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `absent policy adds no supervisor argument`() {
        val property = SupervisorPrewarmBudget.PROPERTY
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
            val manager = SupervisorDaemonManager(MockNativeEngine(), MockSocketManager(), launcher)
            try {
                manager.getOrSpawnSharedDaemon()
                assertFalse(requireNotNull(launcher.lastArgs).any { it.startsWith("-D$property=") })
            } finally {
                manager.stop()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `invalid direct pool policy fails before factory invocation`() {
        val property = SupervisorPrewarmBudget.PROPERTY
        val previous = System.getProperty(property)
        var calls = 0
        try {
            System.setProperty(property, "invalid")
            assertThrows(IllegalArgumentException::class.java) {
                SocketHelperPool(1, 1) {
                    calls++
                    error("invalid policy must not invoke factory")
                }
            }
            assertEquals(0, calls)
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `invalid direct daemon configuration fails before readiness in owned child`() {
        val spec = JvmChildSpec(
            mainClass = InvalidPrewarmDaemonChild::class.java.name,
            extraJvmArgs = listOf("-D${SupervisorPrewarmBudget.PROPERTY}=0"),
            javaAgents = JavaAgentSelection.None,
        )
        val child = ProcessBuilder(JvmChildProcess.commandLine(spec)).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "owned child must exit")
            val bytes = child.inputStream.readNBytes(65537)
            assertTrue(bytes.size <= 65536, "child output must remain bounded")
            val output = bytes.toString(Charsets.UTF_8)
            assertEquals(0, child.exitValue())
            assertTrue(output.contains("INVALID_PREWARM_NO_HELPER=0"))
            assertFalse(output.contains(SupervisorDaemon.DAEMON_READY_SENTINEL))
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "owned child must be reaped")
            }
        }
    }

    @Test
    fun `direct supervisor main rejects invalid budget without readiness`() {
        val spec = JvmChildSpec(
            mainClass = SupervisorDaemon::class.java.name,
            mainArgs = listOf("/unused/invalid-budget.sock"),
            extraJvmArgs = listOf("-D${SupervisorPrewarmBudget.PROPERTY}=0"),
            javaAgents = JavaAgentSelection.None,
        )
        val child = ProcessBuilder(JvmChildProcess.commandLine(spec)).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "owned daemon child must exit")
            val bytes = child.inputStream.readNBytes(65537)
            assertTrue(bytes.size <= 65536, "daemon output must remain bounded")
            val output = bytes.toString(Charsets.UTF_8)
            assertTrue(child.exitValue() != 0)
            assertTrue(output.contains(SupervisorPrewarmBudget.PROPERTY))
            assertFalse(output.contains(SupervisorDaemon.DAEMON_READY_SENTINEL))
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "owned daemon child must be reaped")
            }
        }
    }

    @Test
    fun `explicit budget enters cleanup while helper acquisition is blocked`() {
        val property = "mazewall.supervisor.prewarmTimeoutMillis"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "1000")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val pool = SocketHelperPool(1, 1) {
                entered.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                object : SocketHelper {
                    override fun execute(
                        operation: SocketOperation,
                        descriptor: SupervisorOwnedFd,
                    ): SocketHelperResult = error("prewarm must not execute an operation")

                    override fun close() = Unit
                }
            }
            val worker = Thread {
                try {
                    pool.prewarm()
                } catch (observed: Throwable) {
                    failure.set(observed)
                }
            }
            try {
                worker.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val gateField = SocketHelperPool::class.java.getDeclaredField("gate")
                val sessionsField = SocketHelperPool::class.java.getDeclaredField("sessions")
                check(gateField.trySetAccessible() && sessionsField.trySetAccessible())
                val result = synchronized(gateField.get(pool)) {
                    val session = (sessionsField.get(pool) as Set<*>).single()
                    val jobsField = requireNotNull(session).javaClass.getDeclaredField("jobs")
                    check(jobsField.trySetAccessible())
                    val job = (jobsField.get(session) as Set<*>).single() as SocketHelperPool.Job
                    job.result
                }
                val completed = CountDownLatch(1)
                result.whenComplete { _, _ -> completed.countDown() }
                assertTrue(completed.await(3, TimeUnit.SECONDS), "explicit budget should cancel the blocked job")
                assertTrue(result.isCancelled, "cleanup must cancel the blocked job")
            } finally {
                release.countDown()
                worker.join(3000)
                try {
                    pool.close()
                } finally {
                    assertFalse(worker.isAlive, "prewarm worker must terminate after release")
                }
            }
            assertTrue(failure.get() is IllegalStateException, "expired budget must propagate")
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}

/** A real child process exercises direct construction without installing a containment filter. */
internal object InvalidPrewarmDaemonChild {
    @JvmStatic
    fun main(args: Array<String>) {
        var factoryCalls = 0
        try {
            val pool = SocketHelperPool(1, 1) {
                factoryCalls++
                error("invalid policy must not create a helper")
            }
            try {
                SupervisorDaemonEngine("/unused/invalid-budget.sock", MockNativeEngine(), MockSocketManager(), pool).run()
            } finally {
                pool.close()
            }
            error("invalid policy was accepted")
        } catch (_: IllegalArgumentException) {
            check(factoryCalls == 0)
            println("INVALID_PREWARM_NO_HELPER=$factoryCalls")
        }
    }
}
