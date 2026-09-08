package io.mazewall.profiler.engine

import org.junit.jupiter.api.Test
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonRunnerTest {
    @Test
    fun `missing socket argument exits without constructing an engine`() {
        val exits = mutableListOf<Int>()
        var constructed = false
        val runner = RealDaemonRunner(
            exitFn = exits::add,
            engineFactory = { error("engine must not be constructed").also { constructed = true } },
        )

        runner.runDaemon(emptyArray())

        assertEquals(listOf(1), exits)
        assertTrue(!constructed)
    }

    @Test
    fun `runner registers and removes its shutdown hook through injected lifecycle operations`() {
        val exits = CountDownLatch(1)
        val added = mutableListOf<Thread>()
        val removed = mutableListOf<Thread>()
        val engine = ProfilerDaemonEngine("/tmp/unused.sock")
        val runner = RealDaemonRunner(
            exitFn = { exits.countDown() },
            stdinReader = StringReader(""),
            engineFactory = { engine },
            registerShutdownHook = added::add,
            removeShutdownHook = removed::add,
        )

        val runnerThread = Thread { runner.runDaemon(arrayOf("/tmp/unused.sock")) }
        runnerThread.start()
        assertTrue(exits.await(2, TimeUnit.SECONDS))
        engine.triggerGlobalShutdown("test")
        runnerThread.join(2_000)

        assertEquals(1, added.size)
        assertEquals(added, removed)
    }
}
