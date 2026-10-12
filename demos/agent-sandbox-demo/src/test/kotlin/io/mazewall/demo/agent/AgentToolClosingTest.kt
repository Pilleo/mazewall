package io.mazewall.demo.agent

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Supervision
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AgentToolClosingTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `close cancels admitted analysis whose handle is published after the cancellation snapshot`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "latePublicationBody")
    }

    @Test
    fun `comparison admission rejects after tool closing`() {
        AgentDemoFixture.create().use { fixture ->
            val tools = AgentTools(null, fixture.workspace)
            tools.close()
            assertFailsWith<java.util.concurrent.RejectedExecutionException> { tools.executeDataAnalysis("echo never-enter") }
        }
    }

    @Suppress("unused")
    fun latePublicationBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        Supervision.prepareSupervisor()
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT).build()
        val timeout = Duration.ofSeconds(10)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            AgentDemoFixture.create().use { fixture ->
                AgentTools(runtime, fixture.workspace).use { tools ->
                    val publication = holdPublication(tools)
                    val callers = Executors.newFixedThreadPool(2)
                    val marker = File(fixture.root, "race-child-pid")
                    try {
                        val result = callers.submit<String> { tools.executeDataAnalysis("echo $$ > ${marker.absolutePath}; while :; do :; done") }
                        assertTrue(publication.admitted.await(5, TimeUnit.SECONDS))
                        awaitCondition { marker.exists() && marker.length() > 0L }
                        val child = ProcessHandle.of(marker.readText().trim().toLong()).orElseThrow()
                        assertTrue(child.isAlive)
                        val handle = publication.handle.get()
                        assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                        val workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("mazewall-domain-") }
                        val start = System.nanoTime()
                        val close = callers.submit { tools.close() }
                        // Observe the actual core drain transition while publication is still held outside the tool lock.
                        awaitCondition { runtimeDraining(runtime) }
                        assertFalse(handle.result().toCompletableFuture().isDone)
                        publication.release.countDown()
                        close.get(6, TimeUnit.SECONDS)
                        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "close must retain its shared five-second budget")
                        assertTrue(assertFailsWith<ExecutionException> { result.get(5, TimeUnit.SECONDS) }.cause is CancellationException)
                        assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                        assertFalse(child.isAlive)
                        workers.forEach { assertFalse(it.isAlive) }
                        assertFailsWith<java.util.concurrent.RejectedExecutionException> { tools.executeDataAnalysis("echo never-enter") }
                        assertEquals(DrainReport.Complete, runtime.drain(timeout))
                    } finally {
                        publication.release.countDown()
                        publication.handle.get()?.let { handle ->
                            handle.requestCancellation()
                            handle.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS)
                        }
                        callers.shutdownNow()
                        assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    private fun holdPublication(tools: AgentTools): HeldPublication {
        val field = AgentTools::class.java.getDeclaredField("invocations").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val original = field.get(tools) as MutableSet<InvocationHandle<*>>
        return HeldPublication(original).also { field.set(tools, it) }
    }

    private fun runtimeDraining(runtime: ManagedRuntime): Boolean {
        val lock = ManagedRuntime::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(runtime)
        val phase = ManagedRuntime::class.java.getDeclaredField("phase").apply { isAccessible = true }
        return synchronized(lock) { phase.get(runtime).toString() == "DRAINING" }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        assertTrue(condition(), "fixture handshake did not arrive")
    }
}

private class HeldPublication(private val delegate: MutableSet<InvocationHandle<*>>) : AbstractMutableSet<InvocationHandle<*>>() {
    val admitted = CountDownLatch(1)
    val release = CountDownLatch(1)
    val handle = AtomicReference<InvocationHandle<*>>()
    override val size: Int get() = delegate.size
    override fun iterator(): MutableIterator<InvocationHandle<*>> = delegate.iterator()

    override fun add(element: InvocationHandle<*>): Boolean {
        handle.set(element)
        admitted.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "fixture publication was not released" }
        return delegate.add(element)
    }
}
