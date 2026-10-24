package io.mazewall.portal

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.process.JvmLaunchPreparation
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class AcceptedSocketCancellationIntegrationTest {
    @Test
    fun `cancelled undispatched accept retires after its executor physically ends`(
        @TempDir directory: Path,
    ) {
        IsolatedProcessTester.runIsolatedTest(
            javaClass.name,
            directory.toString(),
            requireNotNull(System.getProperty("io.mazewall.portal.worker.classpath")),
        )
    }

    companion object {
        // A fresh JVM makes the single-carrier scheduling control independent of JUnit's prior work.
        @JvmStatic
        fun main(args: Array<String>) {
            System.setProperty("jdk.virtualThreadScheduler.parallelism", "1")
            System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1")
            System.setProperty("io.mazewall.test", "true")
            System.setProperty("io.mazewall.portal.worker.classpath", args[1])
            runScenario(Path.of(args[0]))
        }

        private fun runScenario(directory: Path) {
            val accepts = AtomicInteger()
            val acquired = CopyOnWriteArrayList<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
            val retired = CopyOnWriteArrayList<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
            val endpoint = AtomicReference<Path>()
            val sockets = object : SocketManager by RealSocketManager {
                override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
                    endpoint.set(Path.of(socketPath))
                    return RealSocketManager.createUnixServer(socketPath).also(acquired::add)
                }

                override fun accept(
                    serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
                    accepts.incrementAndGet()
                    return RealSocketManager.accept(serverFd).also(acquired::add)
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                    RealSocketManager.close(fd)
                    retired.add(fd)
                }
            }
            val child = AtomicReference<Process>()
            val releaseLaunch = CountDownLatch(1)
            val launcher = object : ProcessLauncher by RealProcessLauncher {
                override fun startProcess(
                    args: List<String>,
                    redirectErrorStream: Boolean,
                    preparation: JvmLaunchPreparation,
                ): Process =
                    RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also {
                        child.set(it)
                        assertFalse(releaseLaunch.await(2100, TimeUnit.MILLISECONDS))
                    }
            }
            val broker = ProcessBroker(launcher = launcher, sockets = sockets, limits = PortalRuntimeLimits(startupTimeoutMs = 2000, closeTimeoutMs = 5000))
            val pinPath = directory.resolve("carrier.sock")
            val pinListener = RealSocketManager.createUnixServer(pinPath.toString())
            val pinEntered = CountDownLatch(1)
            val pinFailure = AtomicReference<Throwable>()
            val pin = Thread.ofVirtual().name("portal-accept-carrier-control").unstarted {
                try {
                    pinEntered.countDown()
                    RealSocketManager.close(RealSocketManager.accept(pinListener))
                } catch (failure: Throwable) {
                    pinFailure.set(failure)
                }
            }
            val startupFailure = AtomicReference<Throwable>()
            val startup = Thread.ofPlatform().name("portal-cancelled-accept-startup").unstarted {
                startupFailure.set(runCatching { broker.start() }.exceptionOrNull())
            }
            var primary: Throwable? = null
            try {
                pin.start()
                assertTrue(pinEntered.await(5, TimeUnit.SECONDS))
                startup.start()
                awaitCondition(Duration.ofSeconds(8), "cancelled accept executor entered physical shutdown") {
                    startup.stackTrace.any { it.className == "java.util.concurrent.ThreadPerTaskExecutor" && it.methodName == "awaitTermination" }
                }
                assertEquals(0, accepts.get(), "the cancelled accept producer must not have entered native acquisition")
                SocketChannel.open(UnixDomainSocketAddress.of(pinPath)).use { }
                pin.join(5000)
                assertFalse(pin.isAlive)
                assertEquals(null, pinFailure.get())
                startup.join(8000)
                assertFalse(startup.isAlive)
                val failure = assertNotNull(startupFailure.get(), "expired startup must fail")
                assertTrue(failure is IllegalStateException, failure.stackTraceToString())
                assertTrue(failure.message in setOf("portal worker connection timed out", "portal worker exited before connecting"), failure.stackTraceToString())
                assertEquals(0, accepts.get(), "a cancelled queued producer must not acquire a late socket")
                val actual = requireNotNull(child.get())
                assertFalse(actual.isAlive)
                actual.exitValue()
                assertFalse(Files.exists(Path.of("/proc/${actual.pid()}")))
                assertTrue(acquired.isNotEmpty())
                acquired.forEach { owner -> assertEquals(1, retired.count { it === owner }) }
                assertFalse(Files.exists(requireNotNull(endpoint.get())))
                assertFalse(Files.exists(requireNotNull(endpoint.get()).parent))
                assertTrue(Thread.getAllStackTraces().keys.none { it.name == "portal-worker-output" && it.isAlive })
                broker.close()
                assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
                assertEquals(0, broker.provisionalWorkers())
                assertEquals(0, broker.trackedWorkers())
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                releaseLaunch.countDown()
                val cleanup = CleanupCollector()
                cleanup.attempt("carrier control release") {
                    if (pin.isAlive) SocketChannel.open(UnixDomainSocketAddress.of(pinPath)).use { }
                }
                cleanup.attempt("carrier control join") {
                    pin.join(5000)
                    check(!pin.isAlive)
                }
                cleanup.attempt("carrier control listener") { RealSocketManager.close(pinListener) }
                cleanup.attempt("startup caller join") {
                    startup.join(8000)
                    check(!startup.isAlive)
                }
                cleanup.attempt("broker retirement") { broker.close() }
                cleanup.attempt("fixture child retirement") {
                    child.get()?.let {
                        if (it.isAlive) it.destroyForcibly()
                        check(it.waitFor(2, TimeUnit.SECONDS))
                    }
                }
                cleanup.outcome().failureOrNull()?.let { throw CleanupFailures.merge(primary, it) }
            }
        }
    }
}
