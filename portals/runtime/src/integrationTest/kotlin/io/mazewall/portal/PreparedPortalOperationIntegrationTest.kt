package io.mazewall.portal

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Test-only typed stub for the real worker's builtin endpoint, not generated service proof. */
interface OwnedEchoService {
    fun echo(value: String): String
}

class OwnedEchoServicePortalStub(
    private val broker: ProcessBroker,
) : OwnedEchoService {
    override fun echo(value: String): String = broker.echo(value)
}

class PreparedPortalOperationIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `public prepared operation enforces options and queued cancellation`() {
        val config = configuration(PortalRuntimeLimits(workers = 1, maxAdmissions = 2, maxRequestBytes = 8, maxResponseBytes = 8))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        PortalRuntime.open(config).use { runtime ->
            val echo = runtime.prepare(OwnedEchoService::class.java, PortalServiceId("owned-echo"), PortalOperationCall<OwnedEchoService, String, String> { service, text -> service.echo(text) })
            assertEquals(
                "hello",
                echo
                .submit("hello")
                .result()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS),
            )
            val oversized = echo.submit("123456789")
            assertIs<ExecutionEnd.NotStarted>(oversized.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            val paused = runtime.prepare(
                OwnedEchoService::class.java,
                PortalServiceId("owned-echo"),
                PortalOperationCall<OwnedEchoService, String, String> { service, text ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                service.echo(text)
            },
            )
            try {
                val active = paused.submit("active")
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val queued = echo.submit("queued")
                val overloaded = echo.submit("full")
                assertIs<ExecutionEnd.NotStarted>(overloaded.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(CancellationDisposition.REQUESTED, queued.requestCancellation())
                assertIs<ExecutionEnd.NotStarted>(queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                val next = echo.submit("next")
                release.countDown()
                assertEquals("active", active.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals("next", next.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
            runtime.close()
            assertIs<ExecutionEnd.NotStarted>(
                echo
                .submit("closed")
                .executionEnd()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun `cancellation after actual request progress retains end and admission until cleanup retry`() {
        val blockClose = AtomicBoolean()
        val sockets = object : SocketManager by RealSocketManager {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                if (blockClose.get()) throw IllegalStateException("injected pre-native close failure")
                RealSocketManager.close(fd)
            }
        }
        val broker = ProcessBroker(sockets = sockets, limits = PortalRuntimeLimits(maxAdmissions = 1))
        broker.start()
        val child = ProcessHandle.of(broker.workerPids().single()).orElseThrow()
        val snapshot = LinuxNative.snapshot()
        val requestProgress = CountDownLatch(1)
        LinuxNative.setEngine(object : io.mazewall.platform.native.NativeEngine by snapshot {
            override val memory = object : io.mazewall.platform.native.NativeMemory by snapshot.memory {
            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: io.mazewall.ffi.memory.ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                snapshot.memory.write(fd, buf, count).also { result ->
                    if (result is LinuxNative.SyscallResult.Success && result.value > 0L) requestProgress.countDown()
                }
            }
        })
        try {
            blockClose.set(true)
            val invocation = broker.operations.submit(Callable { broker.sleep(60_000) })
            assertTrue(requestProgress.await(5, TimeUnit.SECONDS))
            assertEquals(CancellationDisposition.REQUESTED, invocation.requestCancellation())
            assertTrue(runCatching { invocation.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.isFailure)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertFalse(child.isAlive, "cancelled process must be reaped despite failed socket cleanup")
            assertFalse(invocation.executionEnd().toCompletableFuture().isDone)
            val refused = broker.operations.submit(Callable { broker.echo("must-not-run") })
            assertIs<ExecutionEnd.NotStarted>(refused.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            blockClose.set(false)
            broker.close()
            assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
        } finally {
            LinuxNative.resetToDefault()
            blockClose.set(false)
            broker.close()
        }
    }

    private fun configuration(limits: PortalRuntimeLimits): PortalRuntimeConfig {
        val bundle = directory.resolve("worker.zip")
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            System.getProperty("io.mazewall.portal.worker.classpath").split(java.io.File.pathSeparator).forEachIndexed { index, file ->
                val path = Path.of(file)
                check(Files.isRegularFile(path) && file.endsWith(".jar")) { "integration worker classpath must contain packaged JARs" }
                zip.putNextEntry(ZipEntry("lib/$index-${path.fileName}"))
                Files.copy(path, zip)
                zip.closeEntry()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bundle)).joinToString("") { "%02x".format(it) }
        val manifest =
            PortalServiceManifest(
                PortalServiceId("owned-echo"),
                OwnedEchoService::class.java.name,
                "fixture.OwnedEchoImpl",
                "fixture.OwnedEchoPortalDispatcher",
                "builtin-echo-fixture",
                "owned-echo",
                PortalInstanceMode.STATELESS,
                "restricted",
                "worker",
            )
        return PortalRuntimeConfig(listOf(manifest), mapOf("worker" to PortalWorkerArtifact(bundle, digest)), directory.resolve("cache"), mapOf("restricted" to PortalWorkerProfile()), limits = limits)
    }
}
