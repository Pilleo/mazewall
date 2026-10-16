package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.engine.JvmFloorWorkload
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class JvmFloorWorkloadTest {
    @Test
    fun `real EPERM from connect is not accepted as floor warmup success`() {
        runFloorChild("1")
    }

    @Test
    fun `real EACCES from connect is not accepted as floor warmup success`() {
        runFloorChild("13")
    }

    @Test
    fun `default one millisecond network probe still completes original floor pressure`() {
        runFloorChild("control")
    }

    @Test
    fun `actual acquired loopback refusal is accepted by full floor classification`() {
        runFloorChild("refusal")
    }

    @Test
    fun `detailed JDK socket refusal is accepted by full floor classification`() {
        runFloorChild("detailed-refusal")
    }

    @Test
    fun `cancellation after all Loom tasks acquired confirms executor physical end and interrupt`() {
        runFloorChild("acquired-loom")
    }

    @Test
    fun `cancellation after OS worker acquisition confirms physical end and interrupt`() {
        runFloorChild("acquired-worker")
    }

    @Test
    fun `fatal acquired OS worker failure outranks owner cancellation after physical end`() {
        runFloorChild("worker-fatal")
    }

    private fun runFloorChild(mode: String) {
        val log = java.nio.file.Files
            .createTempFile("floor-contract-", ".log")
        val builder = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-Xmx1g",
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            JvmFloorWorkloadTest::class.java.name,
            mode,
        ).redirectErrorStream(true).redirectOutput(log.toFile())
        JvmChildProcess.stripInheritedJvmOptions(builder.environment())
        val child = builder.start()
        try {
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), "floor child must complete original work within the existing isolated timeout")
            assertTrue(
                child.exitValue() == 0,
                java.nio.file.Files
                .readString(log),
            )
        } finally {
            child.destroyForcibly()
            assertTrue(child.waitFor(2, TimeUnit.SECONDS), "owned floor child termination must be confirmed")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val mode = args.single()
            if (mode == "control") {
                JvmFloorWorkload.run()
                return
            }
            if (mode == "refusal") {
                acquiredRefusal()
                return
            }
            if (mode == "detailed-refusal") {
                JvmFloorWorkload.runWithNetworkProbe(null) {
                    throw java.net.ConnectException("Connection refused (connect failed)")
                }
                return
            }
            if (mode in setOf("acquired-loom", "acquired-worker", "worker-fatal")) {
                acquiredCancellation(mode)
                return
            }
            val errno = mode.toInt()
            val policy = WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .addAction(SeccompAction.ACT_ERRNO(errno), Syscall.CONNECT)
                .build()
            assertTrue(!Thread.currentThread().isVirtual, "CONNECT denial acquisition must use a disposable platform thread")
            ContainedExecutors.installOnCurrentThread(policy).use { it.requireInstalled() }
            val acquisition = runCatching {
                java.nio.channels.SocketChannel.open().use { channel ->
                    channel.configureBlocking(true)
                    channel.connect(java.net.InetSocketAddress("127.0.0.1", 1))
                }
            }
            val observed = acquisition.exceptionOrNull()
            assertTrue(observed is java.io.IOException, "owned blocking CONNECT must produce an actual errno=$errno permission failure, got $observed")
            val permission = checkNotNull(observed)
            val errnoMessage = checkNotNull(
                io.mazewall.ffi.memory
                .getSystemStrerror(errno),
            )
            assertTrue(permission.message.orEmpty().contains(errnoMessage), "actual permission failure must retain errno=$errno evidence: $permission")
            assertTrue(
                io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
                .diagnose(permission)
                ?.cause === permission,
                    "actual denial evidence must retain its acquired exception identity",
            )
            permission.printStackTrace(System.err)
            val outcome = runCatching {
                JvmFloorWorkload.runWithNetworkProbe(beforeWork = null, networkProbe = { throw permission })
            }
            val failure = outcome.exceptionOrNull()
            assertTrue(failure is java.io.IOException, "actual errno=$errno denial must propagate, got $failure")
            assertTrue(
                io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
                .diagnose(checkNotNull(failure)) != null,
                    "permission evidence must survive",
            )
            assertTrue(failure === permission, "full workload classification must propagate the actual acquired permission exception unchanged")
            failure.printStackTrace(System.err)
        }

        private fun acquiredRefusal() {
            // Own a real loopback port, then close its listener before the blocking CONNECT.
            val address = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use {
                java.net.InetSocketAddress(it.inetAddress, it.localPort)
            }
            val acquisition = runCatching {
                java.nio.channels.SocketChannel.open().use { channel ->
                    channel.configureBlocking(true)
                    channel.connect(address)
                }
            }
            val refusal = acquisition.exceptionOrNull()
            assertTrue(refusal is java.net.ConnectException, "real closed loopback listener must produce refusal, not timeout: $refusal")
            assertTrue(refusal?.message == "Connection refused", "actual refusal classification evidence: $refusal")
            checkNotNull(refusal).printStackTrace(System.err)
            var delivered = false
            JvmFloorWorkload.runWithNetworkProbe(null) {
                delivered = true
                throw refusal
            }
            assertTrue(delivered, "the actual acquired refusal must reach full-pressure classification")
        }

        private fun acquiredCancellation(mode: String) {
            val loom = mode == "acquired-loom"
            val fatal = AssertionError("actual acquired worker fatal after cancellation")
            val tasksStarted = CountDownLatch(if (loom) 100 else 1)
            val taskBlock = CountDownLatch(1)
            val ownerAcquired = CountDownLatch(1)
            val releaseOwner = CompletableFuture<Unit>()
            val executor = AtomicReference<java.util.concurrent.ExecutorService>()
            val worker = AtomicReference<Thread>()
            val outcome = AtomicReference<Throwable?>()
            val restored = java.util.concurrent.atomic
                .AtomicBoolean()
            val barriers = if (loom) {
                JvmFloorWorkload.OwnerBarriers(
                    loomTask = {
                        tasksStarted.countDown()
                        check(taskBlock.await(10, TimeUnit.SECONDS)) { "Loom task cancellation never arrived" }
                    },
                    loomAcquired = {
                        executor.set(it)
                        ownerAcquired.countDown()
                        releaseOwner.orTimeout(10, TimeUnit.SECONDS).join()
                    },
                )
            } else {
                JvmFloorWorkload.OwnerBarriers(
                    workerTask = {
                        tasksStarted.countDown()
                        try {
                            check(taskBlock.await(10, TimeUnit.SECONDS)) { "OS worker cancellation never arrived" }
                        } catch (interruption: InterruptedException) {
                            if (mode == "worker-fatal") throw fatal
                            throw interruption
                        }
                    },
                    workerAcquired = {
                        worker.set(it)
                        ownerAcquired.countDown()
                        releaseOwner.orTimeout(10, TimeUnit.SECONDS).join()
                    },
                )
            }
            val owner = Thread {
                val execution = runCatching { JvmFloorWorkload.runWithOwners(null, {}, barriers) }
                outcome.set(execution.exceptionOrNull())
                restored.set(Thread.currentThread().isInterrupted)
            }
            owner.start()
            try {
                assertTrue(ownerAcquired.await(20, TimeUnit.SECONDS), "actual owner must be acquired before cancellation")
                assertTrue(tasksStarted.await(5, TimeUnit.SECONDS), "actual tasks must be running before owner cancellation")
                if (loom) assertTrue(!executor.get().isTerminated) else assertTrue(worker.get().isAlive)
                owner.interrupt()
                releaseOwner.complete(Unit)
                owner.join(8_000)
                assertTrue(!owner.isAlive, "owner must complete original bounded cleanup")
                if (loom) {
                    assertTrue(executor.get().isTerminated, "shutdown alone is not physical executor end")
                } else {
                    assertTrue(!worker.get().isAlive, "interrupt alone is not physical OS worker end")
                }
                assertTrue(restored.get(), "interrupt must be restored after physical cleanup")
                val failure = outcome.get()
                if (mode == "worker-fatal") {
                    assertTrue(failure === fatal, "actual worker fatal must outrank owner interruption: $failure")
                    assertTrue(fatal.suppressed.any { it is InterruptedException }, "ordinary owner interruption must retain evidence")
                } else {
                    assertTrue(failure is InterruptedException, "acquired cancellation must retain its exception: $failure")
                }
            } finally {
                // Even a failed assertion releases the actual acquired owners; no fixture owner escapes.
                owner.interrupt()
                releaseOwner.complete(Unit)
                taskBlock.countDown()
                owner.join(8_000)
                worker.get()?.join(2_000)
                executor.get()?.awaitTermination(5, TimeUnit.SECONDS)
                assertTrue(!owner.isAlive)
                assertTrue(worker.get()?.isAlive != true)
                assertTrue(executor.get()?.isTerminated != false)
            }
        }
    }

    @Test
    fun `run immediately throws InterruptedException if thread is already interrupted`() {
        val testThread = Thread {
            Thread.currentThread().interrupt()
            try {
                JvmFloorWorkload.run()
            } catch (e: InterruptedException) {
                // Restore interrupt flag so assertThrows or subsequent checks can see it if needed
                Thread.currentThread().interrupt()
                throw e
            }
        }
        testThread.start()
        testThread.join(2000)

        // The thread should have terminated quickly
        assertTrue(!testThread.isAlive, "pre-interrupted workload terminated")
    }

    @Test
    fun `run terminates quickly when interrupted mid-execution`() {
        val startedLatch = CountDownLatch(1)
        val releaseWorkload = CompletableFuture<Unit>()
        val exceptionRef = AtomicReference<Throwable?>(null)

        val testThread = Thread {
            try {
                JvmFloorWorkload.run {
                    startedLatch.countDown()
                    try {
                        // join preserves the interrupt until the real workload checks it.
                        releaseWorkload.orTimeout(5, TimeUnit.SECONDS).join()
                    } catch (failure: CompletionException) {
                        throw AssertionError("Timed out waiting for test to release the started workload", failure)
                    }
                }
            } catch (e: Throwable) {
                exceptionRef.set(e)
            }
        }

        testThread.start()
        try {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "workload entered run before interruption")
            testThread.interrupt()
        } finally {
            releaseWorkload.complete(Unit)
        }
        awaitCondition(description = "interrupted JVM floor workload terminated") { !testThread.isAlive }

        // Assert that the thread did not hang and terminated cleanly
        assertTrue(!testThread.isAlive, "Thread should have terminated after interruption")

        val thrownException = exceptionRef.get()
        assertTrue(thrownException is InterruptedException, "Should have thrown InterruptedException, but got: $thrownException")
    }
}
