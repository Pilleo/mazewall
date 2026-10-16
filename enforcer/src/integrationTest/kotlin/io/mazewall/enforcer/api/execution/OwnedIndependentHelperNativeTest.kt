package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.FileSystemPolicy
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedIndependentHelperNativeTest : BaseIntegrationTest() {
    private companion object {
        const val OWNER_TIMEOUT_SECONDS = 10L
        const val SIGNAL_TIMEOUT_SECONDS = 5L
        const val WORKER_CAPACITY = 1
        const val ADMISSION_CAPACITY = 1
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun independentPrestartedHelperAndHeldLateCallbackRemainOutsideWorkerLandlockAndDrain() =
        withFixtureFiles { file, evidence ->
            Files.writeString(file, "same-file-positive")
            val started = System.nanoTime()
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyIndependentHelper", file.toString(), evidence.toString())
            val reaped = System.nanoTime()
            val proof = Files.readString(evidence)
            val pid = proof
                .lineSequence()
                .first()
                .removePrefix("pid=")
                .toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            val receipt = "$proof\nparentStartNs=$started parentReapedNs=$reaped childAlive=false io=inheritIO/no-pump\n"
            println(receipt)
            System.getProperty("io.mazewall.task5b.evidence")?.let { Files.writeString(Path.of(it), receipt) }
        }

    @Test
    fun failedEvidenceAcquisitionStillDeletesAcquiredData() {
        val data = AtomicReference<Path>()
        val cause = IOException("evidence acquisition cause")
        val failure = IOException("evidence acquisition failure", cause)
        val observed = assertFailsWith<IOException> {
            withFixtureFiles(
                acquireEvidence = { file ->
                    data.set(file)
                    assertTrue(Files.exists(file))
                    throw failure
                },
            ) { _, _ -> error("body entered without evidence acquisition") }
        }
        assertSame(failure, observed)
        assertSame(cause, observed.cause)
        assertFalse(Files.exists(checkNotNull(data.get())))
    }

    @Test
    fun failedEvidenceDeletionStillDeletesDataAndPreservesPrimaryCause() {
        val directory = Files.createTempDirectory("owned-helper-nonempty-evidence-")
        AutoCloseable { Files.deleteIfExists(directory) }.use {
            val child = Files.createFile(directory.resolve("retained-child"))
            AutoCloseable { Files.deleteIfExists(child) }.use {
                val data = AtomicReference<Path>()
                val cause = AccessDeniedException("original body cause")
                val primary = IOException("original body failure", cause)
                val observed = assertFailsWith<IOException> {
                    withFixtureFiles(acquireEvidence = { directory }) { file, evidence ->
                        data.set(file)
                        assertTrue(Files.exists(file) && Files.exists(evidence))
                        throw primary
                    }
                }
                assertSame(primary, observed)
                assertSame(cause, observed.cause)
                assertIs<DirectoryNotEmptyException>(observed.suppressed.single())
                assertFalse(Files.exists(checkNotNull(data.get())))
                assertTrue(Files.exists(child))
            }
        }
        assertFalse(Files.exists(directory))
    }

    private fun withFixtureFiles(
        acquireEvidence: (Path) -> Path = { Files.createTempFile("owned-helper-evidence-", ".txt") },
        action: (Path, Path) -> Unit,
    ) {
        val file = Files.createTempFile("owned-helper-private-", ".txt")
        AutoCloseable { Files.deleteIfExists(file) }.use {
            val evidence = acquireEvidence(file)
            AutoCloseable { Files.deleteIfExists(evidence) }.use { action(file, evidence) }
        }
    }

    // Public only for the existing disposable-JVM reflection runner.
    fun verifyIndependentHelper(
        file: String,
        evidence: String,
    ) {
        val timeout = Duration.ofSeconds(OWNER_TIMEOUT_SECONDS)
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val helperThread = AtomicReference<Thread>()
        val primary = AtomicReference<AccessDeniedException>()
        val proof = StringBuffer("pid=${ProcessHandle.current().pid()}\nfile=$file\n")
        val releaseCallback = CountDownLatch(1)
        assertTrue(LinuxNative.isRealEngineActive())
        record(proof, "native-authority-RealNativeEngine")
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val helper = Executors.newSingleThreadExecutor { task ->
                Thread.ofPlatform().name("task5b-independent-helper").inheritInheritableThreadLocals(false).unstarted(task).also {
                    check(helperThread.compareAndSet(null, it))
                }
            }
            try {
                // Acquiring an executor alone is not prestarting its physical thread.
                val bootstrap = helper.submit(Callable { record(proof, "helper-prestarted-outside-worker-Landlock") }).get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val actualHelper = checkNotNull(helperThread.get())
                assertTrue(actualHelper !== caller && !actualHelper.isVirtual)
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
                val domain = runtime.prepare(policy, DomainLimits(WORKER_CAPACITY, ADMISSION_CAPACITY))
                val handle = runtime.submit(
                    domain,
                    Callable<String> {
                        worker.set(Thread.currentThread())
                        assertTrue(worker.get() !== caller && worker.get() !== actualHelper && !worker.get().isVirtual)
                        val state = ContainmentStateRegistry.threadState
                        // The installer can reuse the identical process Seccomp floor without another filter.
                        assertTrue(ContainmentStateRegistry.resolveCurrentState().filterDepth > 0)
                        assertTrue(state.landlockPolicy?.enforceLandlock == true)
                        assertNull(state.installationFailure)
                        val bodyStart = record(
                            proof,
                            "owned-body-start-installed-Seccomp-Landlock processDepth=${ContainmentStateRegistry.processState.filterDepth} " +
                                "workerDepth=${state.filterDepth} Landlock=true installationFailure=null",
                        )
                        assertTrue(bootstrap < bodyStart)
                        try {
                            assertEquals(
                                "same-file-positive",
                                helper.submit(Callable { readOnHelper(file, actualHelper, proof, "independent-offload-read-positive") }).get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                            )
                            try {
                                Files.readString(Path.of(file))
                                error("owned body unexpectedly read the same denied file")
                            } catch (failure: AccessDeniedException) {
                                primary.set(failure)
                                record(proof, "owned-body-denied-original-AccessDeniedException actualCause=$failure")
                                throw failure
                            }
                        } finally {
                            record(proof, "owned-body-finally-not-yet-ExecutionEnd")
                        }
                    },
                )
                val delivery = assertIs<ContainmentViolationException>(
                    assertFailsWith<ExecutionException> { handle.result().toCompletableFuture().get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.cause,
                )
                assertSame(primary.get(), delivery.cause)
                assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, delivery.evidence)
                assertNull(delivery.errno)
                assertNull(delivery.syscallNr)
                verifyLateCallback(
                    runtime,
                    handle,
                    helper,
                    actualHelper,
                    worker.get(),
                    file,
                    proof,
                    delivery,
                    releaseCallback,
                )
            } finally {
                releaseCallback.countDown()
                helper.shutdownNow()
                check(helper.awaitTermination(OWNER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                helperThread.get()?.let {
                    it.join(TimeUnit.SECONDS.toMillis(SIGNAL_TIMEOUT_SECONDS))
                    assertFalse(it.isAlive)
                }
                record(proof, "independent-helper-actual-thread-joined-dead")
            }
        }
        Files.writeString(Path.of(evidence), proof.toString())
    }

    private fun verifyLateCallback(
        runtime: ManagedRuntime,
        handle: InvocationHandle<String>,
        helper: ExecutorService,
        actualHelper: Thread,
        worker: Thread,
        file: String,
        proof: StringBuffer,
        delivery: ContainmentViolationException,
        releaseCallback: CountDownLatch,
    ) {
        val callbackEntered = CountDownLatch(1)
        val callbackExited = CountDownLatch(1)
        val end = assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertSame(delivery, end.failure)
        val ended = record(proof, "owned-ExecutionEnd-observed-primary-cause-identity-preserved-INFERRED_PERMISSION_FAILURE")
        val scheduled = record(proof, "late-callback-scheduled-after-ExecutionEnd")
        val callback = handle.result().handleAsync(
            { value, failure ->
                assertNull(value)
                assertSame(delivery, (failure as? java.util.concurrent.CompletionException)?.cause ?: failure)
                assertSame(actualHelper, Thread.currentThread())
                val entered = record(proof, "late-callback-entered-held")
                assertTrue(ended < scheduled && scheduled < entered)
                callbackEntered.countDown()
                try {
                    check(releaseCallback.await(OWNER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    readOnHelper(file, actualHelper, proof, "late-callback-read-positive-after-drain")
                } finally {
                    record(proof, "late-callback-finally-not-helper-retirement")
                    callbackExited.countDown()
                }
            },
            helper,
        )
        check(callbackEntered.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(DrainReport.Complete, runtime.drain(Duration.ofSeconds(OWNER_TIMEOUT_SECONDS)))
        assertFalse(worker.isAlive)
        assertTrue(actualHelper.isAlive)
        assertEquals(1L, releaseCallback.count)
        assertEquals(1L, callbackExited.count)
        assertSame(end, handle.executionEnd().toCompletableFuture().get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertSame(delivery, assertFailsWith<ExecutionException> { handle.result().toCompletableFuture().get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.cause)
        val drained = record(proof, "owned-drain-complete-worker-dead-helper-alive-callback-held")
        releaseCallback.countDown()
        assertEquals("same-file-positive", callback.toCompletableFuture().get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        check(callbackExited.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(drained < record(proof, "callback-result-observed-separate-owner-still-live"))
        assertTrue(actualHelper.isAlive)
    }

    private fun readOnHelper(
        file: String,
        expected: Thread,
        proof: StringBuffer,
        event: String,
    ): String {
        assertSame(expected, Thread.currentThread())
        assertNull(ContainmentStateRegistry.threadState.landlockPolicy)
        assertTrue(ContainmentStateRegistry.processState.filterDepth > 0)
        val result = Files.readString(Path.of(file))
        assertEquals("same-file-positive", result)
        record(proof, "$event result=$result outside-worker-Landlock process-Tier1-retained")
        return result
    }

    private fun record(
        proof: StringBuffer,
        event: String,
    ): Long {
        val thread = Thread.currentThread()
        val clock = System.nanoTime()
        proof.append("$event ns=$clock threadId=${thread.threadId()} name=${thread.name} virtual=${thread.isVirtual}\n")
        return clock
    }
}
