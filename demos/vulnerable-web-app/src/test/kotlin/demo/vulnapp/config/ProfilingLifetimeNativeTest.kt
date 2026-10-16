package demo.vulnapp.config

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.platform.syscall.Syscall
import io.mazewall.profiler.api.Profiler
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfilingLifetimeNativeTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `actual wrapped body records a late operation after incomplete publication`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "lateBody")
    }

    @Suppress("unused")
    fun lateBody() {
        val root = Files.createTempDirectory("web-profiler-lifetime-")
        val raw = Executors.newSingleThreadExecutor()
        val wrapper = Profiler.wrap(
            raw,
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .base(Policy.PURE_COMPUTE_UNSAFE)
                .allowMmapExec()
                .build(),
        )
        val owner = ServiceExecutorOwner(Duration.ofMillis(80))
        val manager = MazewallProfileManager(owner)
        val operation = manager.register(raw) { wrapper }
        val entered = CountDownLatch(1)
        val late = CountDownLatch(1)
        val performed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = AtomicReference<Thread>()
        val caller = Thread {
            try {
                operation.call {
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    awaitIgnoringInterrupt(late)
                    Files.createDirectory(root.resolve("late-only"))
                    performed.countDown()
                    awaitIgnoringInterrupt(release)
                }
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        caller.start()
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            val before = wrapper.snapshot()
            assertFalse(Syscall.MKDIR in before.behavior.syscalls || Syscall.MKDIRAT in before.behavior.syscalls)
            assertFalse(before.coverage.complete)
            assertIs<ExecutorDrain.Incomplete>(manager.publish(root.resolve("sbob.json")))
            late.countDown()
            assertTrue(performed.await(10, TimeUnit.SECONDS))
            assertFalse(raw.isTerminated)
            release.countDown()
            caller.join(10_000)
            worker.get().join(10_000)
            assertFalse(caller.isAlive)
            assertFalse(worker.get().isAlive)
            failure.get()?.let { throw it }
            settleProfileOwners()
            val snapshot = wrapper.snapshot()
            assertTrue(Syscall.MKDIR in snapshot.behavior.syscalls || Syscall.MKDIRAT in snapshot.behavior.syscalls, "the late mkdir must be observed by the actual collector")
            if (!snapshot.coverage.complete) {
                kotlin.test.assertFailsWith<IncompleteProfileEvidenceException> { ProfileArtifactPublisher.resolveEvidencePath(root.resolve("sbob.json")) }
            } else {
                kotlin.test.assertEquals(ProfileArtifactPublisher.readCurrent(root.resolve("sbob.json")).evidence, ProfileArtifactPublisher.resolveEvidencePath(root.resolve("sbob.json")))
            }
        } finally {
            late.countDown()
            release.countDown()
            raw.shutdownNow()
            raw.awaitTermination(10, TimeUnit.SECONDS)
            caller.join(10_000)
            settleProfileOwners()
            wrapper.shutdown()
            Profiler.shutdown()
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (latch.count > 0) {
            try {
                latch.await()
            } catch (_: InterruptedException) {
                // Continue collection while the actual body ignores cancellation.
            }
        }
    }
}
