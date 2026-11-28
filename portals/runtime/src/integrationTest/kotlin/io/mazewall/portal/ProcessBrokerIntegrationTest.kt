package io.mazewall.portal

import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Adler32

@Execution(ExecutionMode.SAME_THREAD)
class ProcessBrokerIntegrationTest {
    private companion object {
        val FIXTURE_ROOT = PortalGrantRootId("fixture")
    }

    private class ChildReceipts : ProcessLauncher by RealProcessLauncher {
        val children = java.util.concurrent.CopyOnWriteArrayList<Process>()

        override fun startProcess(
            args: List<String>,
            redirectErrorStream: Boolean,
            preparation: io.mazewall.platform.process.JvmLaunchPreparation,
        ): Process = RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also(children::add)
    }

    private class SocketReceipts(
        private val failNextClose: AtomicBoolean = AtomicBoolean(false),
        private val injectedCloseFailure: Throwable? = null,
    ) : SocketManager by RealSocketManager {
        val acquired = java.util.concurrent.CopyOnWriteArrayList<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val retired = java.util.concurrent.CopyOnWriteArrayList<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()

        override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
            RealSocketManager.createUnixServer(socketPath).also(acquired::add)

        override fun accept(serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
            RealSocketManager.accept(serverFd).also(acquired::add)

        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
            if (failNextClose.compareAndSet(true, false)) {
                injectedCloseFailure?.let { throw it }
            }
            RealSocketManager.close(fd)
            retired.add(fd)
        }

        fun assertAllRetired() {
            assertTrue(acquired.isNotEmpty(), "must observe actual acquired socket owners")
            acquired.forEach { owner ->
                assertEquals(1, retired.count { it === owner }, "each actual acquired socket owner must retire once")
            }
            assertTrue(retired.all { owner -> acquired.any { it === owner } }, "every close must belong to an observed actual owner")
        }
    }

    @Test
    fun `failed launch preserves ordinary and fatal identity and closes actual listener once`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        for (fatalLaunch in listOf(false, true)) {
            for (cleanupFailure in listOf(null, java.io.IOException("listener close denied"), AssertionError("fatal listener close"))) {
                val primary = if (fatalLaunch) AssertionError("fatal launch") else java.io.IOException("launch denied")
                val listeners = mutableListOf<io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>>()
                val closed = mutableListOf<io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>>()
                val paths = mutableListOf<String>()
                val launches = AtomicInteger()
                val closeAttempts = AtomicInteger()
                var rejectedOwner: FileDescriptor<*, FdState.Open, FdOwnership.Owned>? = null
                val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
                    override fun createUnixServer(
                        socketPath: String,
                    ): io.mazewall.platform.fd.FileDescriptor<io.mazewall.platform.fd.FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned> =
                        io.mazewall.platform.socket.RealSocketManager.createUnixServer(socketPath).also {
                            listeners.add(it)
                            paths.add(socketPath)
                        }

                    override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                        if (closeAttempts.incrementAndGet() == 1 && cleanupFailure != null) {
                            rejectedOwner = fd
                            throw cleanupFailure
                        }
                        io.mazewall.platform.socket.RealSocketManager
                            .close(fd)
                        closed.add(fd)
                    }
                }
                val launcher = object : ProcessLauncher by RealProcessLauncher {
                    override fun startProcess(
                        args: List<String>,
                        redirectErrorStream: Boolean,
                        preparation: io.mazewall.platform.process.JvmLaunchPreparation,
                    ): Process {
                        launches.incrementAndGet()
                        throw primary
                    }
                }
                val broker = ProcessBroker(launcher = launcher, sockets = sockets)
                try {
                    val result = runCatching { broker.start() }
                    val expected = if (cleanupFailure is Error && primary !is Error) cleanupFailure else primary
                    assertSame(expected, result.exceptionOrNull(), "actual startup Result must preserve cleanup precedence and exact identity")
                    if (cleanupFailure != null) {
                        assertEquals(1, expected.suppressed.size)
                        assertSame(if (expected === primary) cleanupFailure else primary, expected.suppressed.single())
                        assertSame(listeners.single(), rejectedOwner, "pre-native failure must belong to the actual acquired listener")
                    }
                    assertEquals(1, launches.get(), "failure must occur at the actual launcher boundary")
                    assertEquals(1, listeners.size)
                    assertEquals(1, closeAttempts.get(), "startup rollback must attempt the listener cleanup")
                    assertEquals(if (cleanupFailure == null) 1 else 0, closed.size)
                    if (cleanupFailure == null) assertSame(listeners.single(), closed.single(), "close must consume the actual acquired listener")
                    assertFalse(
                        Files.exists(
                            java.nio.file.Path
                                .of(paths.single()),
                        ),
                        "rollback must unlink the actual socket",
                    )
                    assertFalse(
                        Files.exists(
                            java.nio.file.Path
                                .of(paths.single())
                                .parent,
                        ),
                        "rollback must remove the private endpoint directory",
                    )
                    assertEquals(if (cleanupFailure == null) 0 else 1, broker.provisionalWorkers(), "only the rejected listener owner remains pending after endpoint cleanup")
                    assertEquals(0, broker.trackedWorkers())
                    assertEquals(0, broker.spawnedWorkers(), "failed launch must not publish a worker")
                } finally {
                    broker.close()
                }
                assertEquals(if (cleanupFailure == null) 1 else 2, closeAttempts.get(), "later close retries only the rejected listener")
                assertEquals(0, broker.provisionalWorkers())
                assertSame(listeners.single(), closed.single(), "retry must retire the same actual listener owner")
                assertEquals(1, closed.size, "later broker close must not close the retired listener again")
                broker.close()
                assertEquals(if (cleanupFailure == null) 1 else 2, closeAttempts.get(), "completed retirement must not be dispatched again")
            }
        }
    }

    @Test
    fun `fatal checkout retires child before propagating original error and suppresses cleanup`() {
        val children = java.util.concurrent.CopyOnWriteArrayList<Process>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process = RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also(children::add)
        }
        val cleanup = java.io.IOException("listener cleanup failed")
        val failClose = java.util.concurrent.atomic
            .AtomicBoolean(false)
        val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (failClose.compareAndSet(true, false)) throw cleanup
                io.mazewall.platform.socket.RealSocketManager
                    .close(fd)
            }
        }
        val broker = ProcessBroker(launcher = launcher, sockets = sockets)
        broker.start()
        val child = children.single()
        val runtime = io.mazewall.platform.native.LinuxNative
            .snapshot()
        val fatal = AssertionError("fatal request write")
        io.mazewall.platform.native.LinuxNative.setEngine(object : io.mazewall.platform.native.NativeEngine by runtime {
            override val memory = object : io.mazewall.platform.native.NativeMemory by runtime.memory {
                override fun write(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    buf: io.mazewall.ffi.memory.ManagedSegment,
                    count: Long,
                ): io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> = throw fatal
            }
        })
        try {
            failClose.set(true)
            assertSame(fatal, assertThrows(AssertionError::class.java) { broker.echo("fail") })
            assertFalse(child.isAlive, "fatal return must not leave checked-out process alive")
            assertTrue(fatal.suppressed.contains(cleanup), "channel cleanup must preserve fatal identity")
        } finally {
            io.mazewall.platform.native.LinuxNative
                .resetToDefault()
            broker.close()
        }
    }

    @Test
    fun `join timeout retains late process publication until it is reaped`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val launchEntered = CountDownLatch(1)
        val setupObserved = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        var lateChild: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                launchEntered.countDown()
                setupObserved.countDown()
                assertTrue(releaseLaunch.await(5, TimeUnit.SECONDS))
                return RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also { lateChild = it }
            }
        }
        // Only close/join is under a short deadline; real prelaunch measurement uses the normal startup budget.
        val broker = ProcessBroker(launcher = launcher, limits = PortalRuntimeLimits(closeTimeoutMs = 100))
        val startup = CompletableFuture.runAsync { broker.start() }
        startup.whenComplete { _, _ -> setupObserved.countDown() }
        var primaryFailure: Throwable? = null
        try {
            val observed = setupObserved.await(5, TimeUnit.SECONDS)
            if (!observed || launchEntered.count != 0L) {
                val failure = if (startup.isDone) runCatching { startup.get(0, TimeUnit.SECONDS) }.exceptionOrNull() else null
                throw AssertionError(
                    "launcher entry not observed: startupDone=${startup.isDone}, state=${broker.lifecycleState()}, provisional=${broker.provisionalWorkers()}",
                    failure?.cause ?: failure,
                )
            }

            assertThrows(IncompleteBrokerCleanupException::class.java) { broker.close() }

            assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
            assertEquals(1, broker.provisionalWorkers(), "active acquisition must remain registered after join timeout")
            releaseLaunch.countDown()
            assertThrows(Exception::class.java) { startup.get(10, TimeUnit.SECONDS) }
            awaitCondition(java.time.Duration.ofSeconds(5), "late child reaped") { lateChild?.isAlive == false }
            broker.close()
            assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
            assertEquals(0, broker.provisionalWorkers())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseLaunch.countDown()
            finishLatePublicationFixture(
                primaryFailure,
                awaitStartup = { startup.handle { _, _ -> Unit }.get(10, TimeUnit.SECONDS) },
                closeBroker = { broker.close() },
            )
        }
    }

    private fun finishLatePublicationFixture(
        primaryFailure: Throwable?,
        awaitStartup: () -> Unit,
        closeBroker: () -> Unit,
    ) {
        val cleanupFailures = listOfNotNull(
            runCatching(awaitStartup).exceptionOrNull(),
            runCatching(closeBroker).exceptionOrNull(),
        )
        val reportedFailure = primaryFailure ?: cleanupFailures.firstOrNull() ?: return
        cleanupFailures.filterNot { it === reportedFailure }.forEach(reportedFailure::addSuppressed)
        if (primaryFailure == null) throw reportedFailure
    }

    @Test
    fun `late publication cleanup retains settlement and close failures`() {
        val failure = AssertionError("fixture assertion")
        val settlement = java.util.concurrent.TimeoutException("startup settlement")
        val close = IllegalStateException("broker close")

        finishLatePublicationFixture(failure, { throw settlement }, { throw close })
        assertEquals(listOf(settlement, close), failure.suppressed.toList())

        val reported = assertThrows(java.util.concurrent.TimeoutException::class.java) {
            finishLatePublicationFixture(null, { throw settlement }, { throw close })
        }
        assertSame(settlement, reported)
        assertEquals(listOf(close), reported.suppressed.toList())
    }

    @Test
    fun `interrupted close retains active acquisition for later cleanup`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val launchEntered = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        var lateChild: Process? = null
        val socketReceipts = SocketReceipts()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                launchEntered.countDown()
                assertTrue(releaseLaunch.await(5, TimeUnit.SECONDS))
                return RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also { lateChild = it }
            }
        }
        val broker = ProcessBroker(launcher = launcher, sockets = socketReceipts, limits = PortalRuntimeLimits(startupTimeoutMs = 5_000))
        val startup = CompletableFuture.runAsync { broker.start() }
        val closeReturned = CountDownLatch(1)
        val closeResult = java.util.concurrent.atomic
            .AtomicReference<Result<Unit>?>()
        val closeInterrupted = java.util.concurrent.atomic
            .AtomicBoolean()
        val closer = Thread.ofPlatform().unstarted {
            try {
                closeResult.set(runCatching { broker.close() })
            } finally {
                closeInterrupted.set(Thread.currentThread().isInterrupted)
                closeReturned.countDown()
            }
        }
        try {
            assertTrue(launchEntered.await(5, TimeUnit.SECONDS))
            closer.start()
            awaitCondition(java.time.Duration.ofSeconds(2), "close in awaitBrokerStartup for active launcher acquisition") {
                val stack = closer.stackTrace
                stack.any { it.className == "io.mazewall.portal.ProcessBrokerKt" && it.methodName.startsWith("awaitBrokerStartup") } &&
                    stack.any { it.className == "java.util.concurrent.CountDownLatch" && it.methodName == "await" }
            }
            closer.interrupt()
            assertTrue(closeReturned.await(2, TimeUnit.SECONDS), "close did not return: state=${closer.state}, interrupt=${closer.isInterrupted}, failure=${closeResult.get()?.exceptionOrNull()}")
            val failure = requireNotNull(closeResult.get()).exceptionOrNull()
            if (failure != null && failure !is IncompleteBrokerCleanupException) throw failure
            assertTrue(failure is IncompleteBrokerCleanupException, "actual failure=${failure?.stackTraceToString()}, interrupt=${closeInterrupted.get()}")
            assertTrue(closeInterrupted.get(), "close must preserve interrupt; actual failure=$failure")

            assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
            assertEquals(1, broker.provisionalWorkers())
            releaseLaunch.countDown()
            assertThrows(Exception::class.java) { startup.get(10, TimeUnit.SECONDS) }
            awaitCondition(java.time.Duration.ofSeconds(5), "late interrupted-close child reaped") { lateChild?.isAlive == false }
            broker.close()
            assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
            assertFalse(requireNotNull(lateChild).isAlive, "retry must retire the actual late publication")
            assertEquals(0, broker.provisionalWorkers(), "retry must discharge retained acquisition ownership")
            socketReceipts.assertAllRetired()
        } finally {
            releaseLaunch.countDown()
            closer.join(2000)
            try {
                startup.handle { _, _ -> Unit }.get(10, TimeUnit.SECONDS)
            } finally {
                broker.close()
            }
        }
    }

    @Test
    fun `fatal close still publishes failure and releases concurrent close waiter`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val fatal = AssertionError("fatal pre-native close")
        val attempts = AtomicInteger()
        val socketReceipts = SocketReceipts()
        val childReceipts = ChildReceipts()
        var failedOwner: FileDescriptor<*, FdState.Open, FdOwnership.Owned>? = null
        val sockets = object : SocketManager by socketReceipts {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (attempts.incrementAndGet() == 1) {
                    failedOwner = fd
                    closeEntered.countDown()
                    assertTrue(releaseClose.await(30, TimeUnit.SECONDS))
                    throw fatal
                }
                socketReceipts.close(fd)
            }
        }
        val broker = ProcessBroker(sockets = sockets, launcher = childReceipts)
        broker.start()
        val first = CompletableFuture.runAsync({ broker.close() }, { Thread.ofPlatform().start(it) })
        val waiterResult = CompletableFuture<Unit>()
        val waiter = Thread.ofPlatform().unstarted {
            try {
                broker.close()
                waiterResult.complete(Unit)
            } catch (failure: Throwable) {
                waiterResult.completeExceptionally(failure)
            }
        }
        try {
            assertTrue(closeEntered.await(30, TimeUnit.SECONDS))
            waiter.start()
            awaitCondition(java.time.Duration.ofSeconds(30), "concurrent closer joined shared close") {
                val stack = waiter.stackTrace
                stack.any { it.className == "io.mazewall.portal.ProcessBrokerKt" && it.methodName.startsWith("awaitBrokerCloseOperation") } &&
                    stack.any { it.className == "java.util.concurrent.CompletableFuture" && it.methodName == "get" }
            }
            releaseClose.countDown()

            val thrown = assertThrows(Exception::class.java) { first.get(10, TimeUnit.SECONDS) }
            assertSame(fatal, thrown.cause)
            val waiterThrown = assertThrows(Exception::class.java) { waiterResult.get(5, TimeUnit.SECONDS) }
            assertSame(fatal, waiterThrown.cause, "every closer must observe the shared cleanup failure")
            assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
            assertFalse(childReceipts.children.single().isAlive, "fatal socket close must not prevent actual child retirement")
            assertTrue(socketReceipts.retired.none { it === failedOwner }, "pre-native fatal must not discharge the failed socket owner")

            broker.close()
            assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
            socketReceipts.assertAllRetired()
            assertEquals(1, socketReceipts.retired.count { it === failedOwner }, "retry must retire the exact owner rejected by fatal close")
        } finally {
            releaseClose.countDown()
            try {
                first.handle { _, _ -> Unit }.get(30, TimeUnit.SECONDS)
                if (waiter.state != Thread.State.NEW) waiter.join(30_000)
            } finally {
                broker.close()
            }
        }
    }

    @Test
    fun `close waits for cancelled startup and reaps every provisional child`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val secondLaunchEntered = CountDownLatch(1)
        val releaseSecondLaunch = CountDownLatch(1)
        val children = java.util.concurrent.CopyOnWriteArrayList<Process>()
        val starts = AtomicInteger()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                if (starts.incrementAndGet() == 2) {
                    secondLaunchEntered.countDown()
                    assertTrue(releaseSecondLaunch.await(30, TimeUnit.SECONDS))
                }
                return RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also(children::add)
            }
        }
        val broker = ProcessBroker(limits = PortalRuntimeLimits(workers = 2), launcher = launcher)
        val startup = CompletableFuture.runAsync({ broker.start() }, { Thread.ofPlatform().start(it) })
        val closing = CompletableFuture<Unit>()
        val closer = Thread.ofPlatform().unstarted {
            try {
                broker.close()
                closing.complete(Unit)
            } catch (failure: Throwable) {
                closing.completeExceptionally(failure)
            }
        }
        try {
            assertTrue(secondLaunchEntered.await(30, TimeUnit.SECONDS))
            closer.start()
            awaitCondition(java.time.Duration.ofSeconds(30), "close awaiting cancelled startup") {
                val stack = closer.stackTrace
                broker.lifecycleState() == ProcessBrokerLifecycle.Closing &&
                    stack.any { it.className == "io.mazewall.portal.ProcessBrokerKt" && it.methodName.startsWith("awaitBrokerStartup") } &&
                    stack.any { it.className == "java.util.concurrent.CountDownLatch" && it.methodName == "await" }
            }
            assertFalse(closing.isDone, "close must join the startup owner before publishing Closed")
            releaseSecondLaunch.countDown()

            assertThrows(Exception::class.java) { startup.get(10, TimeUnit.SECONDS) }
            closing.get(10, TimeUnit.SECONDS)
            assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
            assertTrue(children.none(Process::isAlive), "all provisional children must be reaped")
        } finally {
            releaseSecondLaunch.countDown()
            try {
                startup.handle { _, _ -> Unit }.get(30, TimeUnit.SECONDS)
                if (closer.state != Thread.State.NEW) closer.join(30_000)
            } finally {
                broker.close()
            }
        }
    }

    @Test
    fun `failed provisional rollback is retained for close retry`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val starts = AtomicInteger()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                if (starts.incrementAndGet() == 2) error("second worker launch failed")
                return RealProcessLauncher.startProcess(args, redirectErrorStream, preparation)
            }
        }
        val closes = AtomicInteger()
        val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (closes.incrementAndGet() == 1) throw java.io.IOException("pre-native channel close")
                io.mazewall.platform.socket.RealSocketManager
                    .close(fd)
            }
        }
        val broker = ProcessBroker(limits = PortalRuntimeLimits(workers = 2), launcher = launcher, sockets = sockets)

        assertThrows(IllegalStateException::class.java) { broker.start() }
        assertEquals(1, broker.provisionalWorkers(), "unfinished rollback owner must remain registered")

        broker.close()

        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
        assertEquals(0, broker.provisionalWorkers())
        assertEquals(4, closes.get(), "retry only the provisional listener whose pre-native close failed")
    }

    @Test
    fun `fatal provisional rollback remains retryable after startup publishes failure`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val starts = AtomicInteger()
        val primary = IllegalStateException("second worker launch failed")
        var firstChild: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                if (starts.incrementAndGet() == 2) throw primary
                return RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also { firstChild = it }
            }
        }
        val fatal = AssertionError("fatal provisional listener close")
        val closes = AtomicInteger()
        var failedOwner: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>? = null
        val retiredOwners = mutableListOf<io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>>()
        val sockets = object : io.mazewall.platform.socket.SocketManager by io.mazewall.platform.socket.RealSocketManager {
            override fun close(fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>) {
                if (closes.incrementAndGet() == 1) {
                    failedOwner = fd
                    throw fatal
                }
                io.mazewall.platform.socket.RealSocketManager
                    .close(fd)
                retiredOwners.add(fd)
            }
        }
        val broker = ProcessBroker(limits = PortalRuntimeLimits(workers = 2), launcher = launcher, sockets = sockets)

        assertSame(fatal, assertThrows(AssertionError::class.java) { broker.start() })
        assertTrue(fatal.suppressed.any { it === primary }, "fatal listener cleanup must retain the exact failed-launch primary")
        assertFalse(requireNotNull(firstChild).isAlive, "fatal rollback must still physically retire the already acquired child")
        assertTrue(retiredOwners.none { it === failedOwner }, "pre-native fatal close must retain its actual owner for retry")
        assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
        assertEquals(1, broker.provisionalWorkers())

        broker.close()

        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
        assertEquals(0, broker.provisionalWorkers())
        assertEquals(1, retiredOwners.count { it === failedOwner }, "retry must close the exact retained listener once")
        assertTrue(retiredOwners.indices.all { index -> retiredOwners.take(index).none { it === retiredOwners[index] } }, "no actual socket owner may be closed twice")
        broker.close()
        assertEquals(1, retiredOwners.count { it === failedOwner }, "idempotent close must not repeat native retirement")
    }

    @Test
    fun `second worker startup failure rolls back the unpublished first worker`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val starts = AtomicInteger()
        var first: Process? = null
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                if (starts.incrementAndGet() == 2) error("second worker launch failed")
                return RealProcessLauncher.startProcess(args, redirectErrorStream, preparation).also { first = it }
            }
        }
        val broker = ProcessBroker(limits = PortalRuntimeLimits(workers = 2), launcher = launcher)

        assertThrows(IllegalStateException::class.java) { broker.start() }

        assertEquals(ProcessBrokerLifecycle.Failed("second worker launch failed"), broker.lifecycleState())
        assertEquals(0, broker.trackedWorkers())
        assertEquals(0, broker.idleSize())
        assertTrue(first?.isAlive == false, "provisional first worker must be destroyed and reaped")
        broker.close()
    }

    @Test
    fun `worker connection and readiness share one bounded startup deadline`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val launcherEntered = AtomicBoolean()
        val child = AtomicReference<Process>()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                launcherEntered.set(true)
                return ProcessBuilder("sleep", "60").start().also(child::set)
            }
        }
        val reapFixtureChild = AutoCloseable {
            child.get()?.takeIf(Process::isAlive)?.let { process ->
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Fixture worker ${process.pid()} did not retire" }
            }
        }

        reapFixtureChild.use {
            ProcessBroker(launcher = launcher, limits = PortalRuntimeLimits(startupTimeoutMs = 150)).use { broker ->
                val startedAt = System.nanoTime()
                val startupFailure = assertThrows(IllegalStateException::class.java) { broker.start() }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                val worker = child.get()
                    ?: throw AssertionError("Startup failed without a published worker (launcher entered=${launcherEntered.get()})", startupFailure)
                if (worker.isAlive) throw AssertionError("Timed-out worker ${worker.pid()} is still alive", startupFailure)
                if (elapsedMs >= 3_000) throw AssertionError("Startup deadline escaped after ${elapsedMs}ms", startupFailure)
                assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
            }
        }
    }

    @Test
    fun `worker checkout consumes the same budget as the subsequent RPC`() {
        ProcessBroker(limits = PortalRuntimeLimits(callTimeoutMs = 2_000)).use { broker ->
            broker.start()
            val secondReady = CountDownLatch(1)
            val startSecond = CountDownLatch(1)
            val firstWrite = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val firstThread = AtomicReference<Thread>()
            val gateOnce = AtomicBoolean(true)
            val secondResult = CompletableFuture<Unit>()
            val second = Thread.ofPlatform().start {
                try {
                    secondReady.countDown()
                    assertTrue(startSecond.await(30, TimeUnit.SECONDS))
                    val failure = assertThrows(PortalCallException::class.java) { broker.sleep(1_500) }
                    assertEquals(PortalInvocationEvidence.UNKNOWN_COMPLETION, failure.evidence)
                    assertTrue(failure.cause is PortalReadTimeoutException, "Second request must reach reply wait after checkout: $failure")
                    secondResult.complete(Unit)
                } catch (failure: Throwable) {
                    secondResult.completeExceptionally(failure)
                }
            }
            val runtime = LinuxNative.snapshot()
            LinuxNative.setEngine(object : io.mazewall.platform.native.NativeEngine by runtime {
                override val memory = object : io.mazewall.platform.native.NativeMemory by runtime.memory {
                    override fun write(
                        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                        buf: io.mazewall.ffi.memory.ManagedSegment,
                        count: Long,
                    ): io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> {
                        if (Thread.currentThread() === firstThread.get() && gateOnce.compareAndSet(true, false)) {
                            firstWrite.countDown()
                            check(releaseWrite.await(2, TimeUnit.SECONDS))
                        }
                        return runtime.memory.write(fd, buf, count)
                    }
                }
            })
            var first: CompletableFuture<Void>? = null
            try {
                assertTrue(secondReady.await(30, TimeUnit.SECONDS))
                first = CompletableFuture.runAsync({
                    firstThread.set(Thread.currentThread())
                    broker.sleep(1_500)
                }, { Thread.ofPlatform().start(it) })
                assertTrue(firstWrite.await(2, TimeUnit.SECONDS), "first caller must reach request write after checkout")
                startSecond.countDown()
                awaitCondition(java.time.Duration.ofSeconds(2), "second caller blocked in checkout") {
                    val stack = second.stackTrace
                    stack.any { it.className == "io.mazewall.portal.ProcessBroker" && it.methodName.startsWith("checkoutWorker-") } &&
                        stack.any { it.className == "java.util.concurrent.ArrayBlockingQueue" && it.methodName == "poll" }
                }
                releaseWrite.countDown()
                secondResult.get(5, TimeUnit.SECONDS)
                first.get(5, TimeUnit.SECONDS)
            } finally {
                startSecond.countDown()
                releaseWrite.countDown()
                try {
                    first?.handle { _, _ -> Unit }?.get(10, TimeUnit.SECONDS)
                    second.join(10_000)
                } finally {
                    LinuxNative.setEngine(runtime)
                }
            }
        }
    }

    @Test
    fun `interrupted capability admission preserves interruption and never reuses the partial-frame worker`() {
        val fixture = Files.createTempFile("mazewall-portal-interrupt-", ".txt")
        Files.writeString(fixture, "blocked grant")
        val receipts = ChildReceipts()
        val socketReceipts = SocketReceipts()
        ProcessBroker(
            launcher = receipts,
            sockets = socketReceipts,
            limits = PortalRuntimeLimits(callTimeoutMs = 5_000),
            grantRoots = PortalGrantRoots.of(PortalGrantRoot(FIXTURE_ROOT, fixture.parent)),
        ).use { broker ->
            broker.start()
            val interruptedChild = receipts.children.single()
            val capability = broker.openReadOnly(FIXTURE_ROOT, fixture.fileName.toString())
            val grantedFdPath = capability.borrow { fd ->
                java.nio.file.Path.of("/proc/self/fd/${fd.value}").also { path ->
                    assertEquals(fixture.toRealPath(), Files.readSymbolicLink(path), "native grant must pin the actual fixture")
                }
            }
            val borrowEntered = CountDownLatch(1)
            val releaseBorrow = CountDownLatch(1)
            val borrower = CompletableFuture.runAsync {
                capability.borrow {
                    borrowEntered.countDown()
                    assertTrue(releaseBorrow.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(borrowEntered.await(2, TimeUnit.SECONDS))
            val interrupted = java.util.concurrent.atomic
                .AtomicReference<Result<Int>?>()
            val interruptPreserved = java.util.concurrent.atomic
                .AtomicBoolean()
            val waiting = Thread.ofPlatform().start {
                interrupted.set(runCatching { broker.checksum(capability) })
                interruptPreserved.set(Thread.currentThread().isInterrupted)
            }
            awaitCondition(java.time.Duration.ofSeconds(2), "caller admitted channel before blocked capability") {
                broker.idleSize() == 0 && waiting.state == Thread.State.TIMED_WAITING
            }
            waiting.interrupt()
            waiting.join(2_000)
            releaseBorrow.countDown()
            borrower.get(2, TimeUnit.SECONDS)
            capability.close()
            val remainingTarget = try {
                Files.readSymbolicLink(grantedFdPath)
            } catch (_: java.nio.file.NoSuchFileException) {
                null
            }
            assertFalse(remainingTarget == fixture.toRealPath(), "actual kernel-issued grant must retire before fixture deletion; integer reuse for another owner is permitted")
            Files.deleteIfExists(fixture)

            assertFalse(waiting.isAlive)
            val failure = requireNotNull(interrupted.get()).exceptionOrNull()
            if (failure != null && failure !is PortalCallException) throw failure
            assertTrue(interruptPreserved.get(), "broker must restore the interrupted flag")
            assertTrue(failure is PortalCallException)
            assertTrue(failure?.cause is InterruptedException)
            awaitCondition(java.time.Duration.ofSeconds(10), "interrupted partial-frame worker replaced") {
                broker.idleSize() == 1 && broker.spawnedWorkers() == 2
            }
            assertFalse(interruptedChild.isAlive, "replacement must follow physical partial-frame retirement")
            assertEquals("still-usable", broker.echo("still-usable"))
        }
        assertTrue(receipts.children.all { !it.isAlive }, "close must reap both original and replacement receipts")
        socketReceipts.assertAllRetired()
    }

    @Test
    fun `interrupted idle checkout preserves interruption without retiring an unclaimed worker`() {
        val receipts = ChildReceipts()
        val socketReceipts = SocketReceipts()
        ProcessBroker(launcher = receipts, sockets = socketReceipts, limits = PortalRuntimeLimits(callTimeoutMs = 5_000)).use { broker ->
            broker.start()
            val activeChild = receipts.children.single()
            val activeOwners = socketReceipts.acquired.toList()
            val active = CompletableFuture.runAsync { broker.sleep(2_000) }
            awaitCondition(java.time.Duration.ofSeconds(2), "worker checked out") { broker.idleSize() == 0 }
            val interrupted = java.util.concurrent.atomic
                .AtomicReference<Result<String>?>()
            val interruptPreserved = java.util.concurrent.atomic
                .AtomicBoolean()
            val waiting = Thread.ofPlatform().start {
                interrupted.set(runCatching { broker.echo("must-not-run") })
                interruptPreserved.set(Thread.currentThread().isInterrupted)
            }
            awaitCondition(java.time.Duration.ofSeconds(2), "second caller waiting for worker") {
                waiting.state == Thread.State.TIMED_WAITING || waiting.state == Thread.State.WAITING
            }
            waiting.interrupt()
            waiting.join(2_000)

            assertFalse(waiting.isAlive)
            val failure = requireNotNull(interrupted.get()).exceptionOrNull()
            if (failure != null && failure !is PortalCallException) throw failure
            assertTrue(interruptPreserved.get(), "broker must restore the interrupted flag")
            assertTrue(failure is PortalCallException)
            assertTrue(failure?.cause is InterruptedException)
            assertTrue(activeChild.isAlive, "unclaimed waiter interruption must not retire the active caller's child")
            assertTrue(activeOwners.none { owner -> socketReceipts.retired.any { it === owner } }, "unclaimed interruption must not close the active worker's actual sockets")
            active.get(5, TimeUnit.SECONDS)
            assertEquals(1, broker.spawnedWorkers())
            assertEquals("still-usable", broker.echo("still-usable"))
        }
        assertFalse(receipts.children.single().isAlive, "owner close must reap the same retained child")
        socketReceipts.assertAllRetired()
    }

    @Test
    fun `granted-fd checksum works through a startup-authorized root`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        assumeTrue(
            java.nio.file.Files
            .exists(
                java.nio.file.Path
                .of("/etc/passwd"),
            ),
        )
        val payload = Files.createTempFile("mazewall-portal-", ".bin")
        Files.write(payload, byteArrayOf(1, 2, 3, 4, 5))
        ProcessBroker(grantRoots = PortalGrantRoots.of(PortalGrantRoot(FIXTURE_ROOT, payload.parent))).use { broker ->
            broker.start()
            assertEquals(1, broker.spawnedWorkers())
            val expected = Adler32().also { it.update(byteArrayOf(1, 2, 3, 4, 5)) }.value.toInt()
            broker.openReadOnly(FIXTURE_ROOT, payload.fileName.toString()).use { granted ->
                assertEquals(expected, broker.checksum(granted))
            }
            assertEquals("after-landlock-deny", broker.echo("after-landlock-deny"))
        }
        Files.deleteIfExists(payload)
    }

    @Test
    fun `echo and granted-fd checksum round trip`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val payload = Files.createTempFile("mazewall-portal-", ".bin")
        Files.write(payload, byteArrayOf(1, 2, 3, 4, 5))
        ProcessBroker(grantRoots = PortalGrantRoots.of(PortalGrantRoot(FIXTURE_ROOT, payload.parent))).use { broker ->
            broker.start()
            assertEquals("hello-portal", broker.echo("hello-portal"))
            val expected = Adler32().also { it.update(byteArrayOf(1, 2, 3, 4, 5)) }.value.toInt()
            broker.openReadOnly(FIXTURE_ROOT, payload.fileName.toString()).use { granted ->
                assertEquals(expected, broker.checksum(granted))
            }
        }
        Files.deleteIfExists(payload)
    }

    @Test
    fun `pool of two serves concurrent echoes`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(limits = PortalRuntimeLimits(workers = 2)).use { broker ->
            broker.start()
            val a = CompletableFuture.supplyAsync { broker.echo("alpha") }
            val b = CompletableFuture.supplyAsync { broker.echo("beta") }
            assertEquals("alpha", a.get(20, TimeUnit.SECONDS))
            assertEquals("beta", b.get(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `configured admission rejects overlap without consuming worker transport`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val limits = PortalRuntimeLimits(workers = 1, maxAdmissions = 1, callTimeoutMs = 5_000L)
        ProcessBroker(limits = limits).use { broker ->
            broker.start()
            val active = CompletableFuture.runAsync { broker.sleep(500) }
            awaitCondition(description = "bounded caller checked out worker") { broker.idleSize() == 0 }
            val rejected = assertThrows(PortalCallException::class.java) { broker.echo("must-not-send") }
            assertEquals(PortalInvocationEvidence.NOT_STARTED, rejected.evidence)
            active.get(5, TimeUnit.SECONDS)
            assertEquals("after-admission", broker.echo("after-admission"))
        }
    }

    @Test
    fun `stateful timeout retires worker without recreating guest state`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(limits = PortalRuntimeLimits(callTimeoutMs = 200), instanceMode = PortalInstanceMode.STATEFUL_SINGLETON).use { broker ->
            broker.start()
            val child = ProcessHandle.of(broker.workerPids().single()).orElseThrow()
            val failed = assertThrows(PortalCallException::class.java) { broker.sleep(10_000) }
            assertEquals(PortalInvocationEvidence.UNKNOWN_COMPLETION, failed.evidence)
            awaitCondition(java.time.Duration.ofSeconds(5), "stateful worker retirement") { broker.trackedWorkers() == 0 }
            assertFalse(child.isAlive)
            assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
            assertThrows(IllegalStateException::class.java) { broker.echo("must-not-recreate") }
            assertEquals(1, broker.spawnedWorkers())
            assertEquals(0, broker.idleSize())
            broker.close()
            assertEquals(1, broker.spawnedWorkers(), "joined maintenance must never construct replacement state")
        }
    }

    @Test
    fun `stateful crash preserves lifetime spawn count captured before loss through joined close`() {
        ProcessBroker(instanceMode = PortalInstanceMode.STATEFUL_SINGLETON).use { broker ->
            broker.start()
            assertEquals("before-crash", broker.echo("before-crash"))
            val original = ProcessHandle.of(broker.workerPids().single()).orElseThrow()
            val beforeCrash = broker.spawnedWorkers()
            assertEquals(1, beforeCrash)
            broker.crashIdleWorkerProcess()
            val failure = assertThrows(PortalCallException::class.java) { broker.echo("must-not-replay") }
            assertEquals(PortalInvocationEvidence.UNKNOWN_COMPLETION, failure.evidence)
            broker.close() // Joins the physical maintenance owner, not a timed process snapshot.
            assertFalse(original.isAlive)
            assertEquals(beforeCrash, broker.spawnedWorkers(), "no replacement, including short-lived or pre-delivery spawns")
        }
    }

    @Test
    fun `timeout kills worker and later call succeeds`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(limits = PortalRuntimeLimits(callTimeoutMs = 800)).use { broker ->
            broker.start()
            assertThrows(PortalCallException::class.java) { broker.sleep(10_000) }
            awaitCondition(java.time.Duration.ofSeconds(10), "timed-out worker replacement") { broker.idleSize() == 1 }
            assertEquals("after-timeout", broker.echo("after-timeout"))
        }
    }

    @Test
    fun `crashed worker is replaced and later call succeeds`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker().use { broker ->
            broker.start()
            broker.crashIdleWorkerProcess()
            assertThrows(PortalCallException::class.java) { broker.echo("fail-inflight") }
            awaitCondition(java.time.Duration.ofSeconds(10), "crashed worker replacement") { broker.idleSize() == 1 }
            assertEquals("after-crash", broker.echo("after-crash"))
        }
    }

    @Test
    fun `close destroys checked-out workers instead of orphaning them`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val receipts = ChildReceipts()
        val socketReceipts = SocketReceipts()
        ProcessBroker(launcher = receipts, sockets = socketReceipts).use { broker ->
            broker.start()
            val checkedOutChild = receipts.children.single()
            // Check out the only worker via a long sleep on a separate thread.
            val inFlight = CompletableFuture.runAsync { broker.sleep(60_000) }
            // Wait until the slot is actually checked out (idle queue empty).
            awaitCondition(description = "portal caller checked out the sole worker") {
                broker.idleSize() == 0
            }
            assertEquals(0, broker.idleSize(), "worker should be checked out by now")

            broker.close()
            assertFalse(checkedOutChild.isAlive, "close must physically reap the actual checked-out child before return")
            socketReceipts.assertAllRetired()

            // In-flight caller must fail fast instead of hanging on a destroyed channel.
            val result = runCatching { inFlight.get(10, TimeUnit.SECONDS) }
            val failure = result.exceptionOrNull()
            if (failure != null && failure !is java.util.concurrent.ExecutionException) throw failure
            if (failure?.cause is Error) throw requireNotNull(failure.cause)
            assertTrue(failure is java.util.concurrent.ExecutionException, "in-flight call must abort on close: $result")
            assertTrue(failure?.cause is PortalCallException, "close must abort the actual portal call: $failure")
        }

        // The decisive assertion: no portal worker JVM survives its broker.
        val deadline = System.currentTimeMillis() + 5_000
        var survivors: List<ProcessHandle>
        do {
            survivors = ProcessHandle
                .current()
                .descendants()
                .filter { h ->
                    h
                        .info()
                        .commandLine()
                        .orElse("")
                        .contains("io.mazewall.portal.worker.PortalWorkerMain")
                }.toList()
        } while (survivors.isNotEmpty() && System.currentTimeMillis() < deadline)

        assertEquals(0, survivors.size, "checked-out workers must be destroyed on close: $survivors")
    }

    @Test
    fun `recycled worker is retired before asynchronous replacement`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        ProcessBroker(limits = PortalRuntimeLimits(workers = 2)).use { broker ->
            broker.start()
            assertEquals(2, broker.trackedWorkers())
            broker.crashIdleWorkerProcess()

            assertEquals("still-alive", broker.echo("still-alive"))
            assertThrows(PortalCallException::class.java) { broker.echo("dead-worker") }
            awaitCondition(java.time.Duration.ofSeconds(10), "replacement after corpse retirement") {
                broker.trackedWorkers() == 2 && broker.idleSize() == 2
            }
            assertEquals(3, broker.spawnedWorkers(), "one worker should replace the reaped corpse")
        }
    }

    @Test
    fun `replacement rejected during close remains owned after a retryable cleanup failure`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val replacementReady = CountDownLatch(1)
        val releaseReplacement = CompletableFuture<Unit>()
        val receipts = ChildReceipts()
        val socketReceipts = SocketReceipts()
        val closeAttempts = AtomicInteger()
        val failure = java.io.IOException("replacement listener close failed before dispatch")
        val sockets = object : SocketManager by socketReceipts {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                if (fd === socketReceipts.acquired.getOrNull(2) && closeAttempts.incrementAndGet() == 1) throw failure
                socketReceipts.close(fd)
            }
        }
        lateinit var broker: ProcessBroker
        val launcher = object : ProcessLauncher by receipts {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                val child = receipts.startProcess(args, redirectErrorStream, preparation)
                if (receipts.children.size == 1) return child
                return object : Process() {
                    override fun getOutputStream() = child.outputStream

                    override fun getInputStream() = child.inputStream

                    override fun getErrorStream() = child.errorStream

                    override fun waitFor() = child.waitFor()

                    override fun waitFor(
                        timeout: Long,
                        unit: TimeUnit,
                    ) = child.waitFor(timeout, unit)

                    override fun exitValue() = child.exitValue()

                    override fun destroy() = child.destroy()

                    override fun destroyForcibly() = apply { child.destroyForcibly() }

                    override fun isAlive() = child.isAlive

                    override fun toHandle() = child.toHandle()

                    override fun pid(): Long {
                        // This diagnostic read follows the acquisition handoff and precedes pool publication.
                        if (broker.spawnedWorkers() == 2 && replacementReady.count != 0L) {
                            replacementReady.countDown()
                            releaseReplacement.orTimeout(10, TimeUnit.SECONDS).join()
                            Thread.interrupted()
                        }
                        return child.pid()
                    }
                }
            }
        }
        broker = ProcessBroker(launcher = launcher, sockets = sockets)
        var closing: CompletableFuture<Throwable?>? = null
        try {
            broker.start()
            broker.crashIdleWorkerProcess()
            assertThrows(PortalCallException::class.java) { broker.echo("trigger replacement") }
            assertTrue(replacementReady.await(10, TimeUnit.SECONDS), "replacement must finish acquisition")
            assertEquals(0, broker.idleSize(), "replacement must not be published during the test pause")
            closing = CompletableFuture.supplyAsync { runCatching { broker.close() }.exceptionOrNull() }
            awaitCondition(java.time.Duration.ofSeconds(5), "broker starts closing before replacement publication") {
                broker.lifecycleState() == ProcessBrokerLifecycle.Closing
            }
            releaseReplacement.complete(Unit)

            assertSame(failure, closing.get(10, TimeUnit.SECONDS))
            assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
            assertEquals(1, broker.provisionalWorkers(), "retryable listener must retain its replacement owner")
            assertEquals(1, closeAttempts.get())
            assertTrue(receipts.children.none(Process::isAlive), "independent child cleanup must finish")
            broker.close()
            assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
            assertEquals(0, broker.provisionalWorkers())
            assertEquals(2, closeAttempts.get(), "retry must close only the unfinished listener")
            socketReceipts.assertAllRetired()
        } finally {
            releaseReplacement.complete(Unit)
            closing?.get(10, TimeUnit.SECONDS)
            try {
                broker.close()
            } finally {
                socketReceipts.acquired.getOrNull(2)?.let { listener ->
                    if (socketReceipts.retired.none { it === listener }) RealSocketManager.close(listener)
                }
            }
        }
    }

    @Test
    fun `worker survives multiple idle ticks and still serves calls`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        // Regression for the pooled-worker self-exit bug (011654): a 500ms receive deadline
        // fires twice during this sleep. Keep the request away from the exact deadline edge:
        // once a header byte arrives, expiry is correctly a terminal partial-frame timeout.
        val broker = ProcessBroker(
            launchProfile = PortalLaunchProfile(jvmArgs = listOf("-Dio.mazewall.portal.worker.idleTimeoutMs=500")),
        )
        broker.use { b ->
            b.start()
            Thread.sleep(1_250) // Workload delay: keep the worker idle across multiple real receive-timeout intervals.
            assertEquals("after-idle", b.echo("after-idle"))
            assertEquals(1, b.spawnedWorkers(), "idle ticks must not respawn workers")
        }
    }

    @Test
    fun `malformed generated reply holds checkout during validation retires exact child and never replays`(
        @TempDir directory: Path,
    ) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val fixture = validatedReplyBrokerFixture(directory, mode = PortalInstanceMode.STATELESS)
        val receipts = ChildReceipts()
        val socketReceipts = SocketReceipts()
        val method = PortalMethod.Generated(4242)
        ProcessBroker(
            launcher = receipts,
            sockets = socketReceipts,
            workerClasspath = fixture.brokerClasspath,
            workerLaunch = fixture.workerLaunch,
            limits = PortalRuntimeLimits(workers = 1, maxAdmissions = 2, callTimeoutMs = 10_000),
        ).use { broker ->
            broker.start()
            val originalChild = receipts.children.single()
            val originalPid = originalChild.pid()
            val enteredValidation = CountDownLatch(1)
            val releaseValidation = CountDownLatch(1)

            val firstCall = CompletableFuture.supplyAsync {
                broker.invokeValidated(method, PortalCodec.encodeString("malformed-trailing")) { bytes ->
                    enteredValidation.countDown()
                    check(releaseValidation.await(5, TimeUnit.SECONDS))
                    val reader = PortalCodec.Reader(bytes, "validated.return")
                    val text = reader.string()
                    reader.requireExhausted()
                    text
                }
            }
            assertTrue(enteredValidation.await(5, TimeUnit.SECONDS))
            assertEquals(0, broker.idleSize(), "checked-out worker must stay out of idle pool during reply validation")

            val competingResult = AtomicReference<Result<String>?>()
            val competingThread = Thread.ofPlatform().start {
                competingResult.set(
                    runCatching {
                        broker.invokeValidated(method, PortalCodec.encodeString("valid")) { bytes ->
                            val reader = PortalCodec.Reader(bytes, "validated.return")
                            val text = reader.string()
                            reader.requireExhausted()
                            text
                        }
                    },
                )
            }
            awaitCondition(java.time.Duration.ofSeconds(5), "competing caller blocked waiting for worker checkout") {
                (competingThread.state == Thread.State.TIMED_WAITING || competingThread.state == Thread.State.WAITING) &&
                    competingThread.stackTrace.any { it.className == "io.mazewall.portal.ProcessBroker" && it.methodName.startsWith("checkoutWorker") } &&
                    competingResult.get() == null &&
                    broker.idleSize() == 0
            }

            releaseValidation.countDown()
            val firstFailure = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                firstCall.get(10, TimeUnit.SECONDS)
            }.cause as PortalMalformedPayloadException
            assertEquals(PortalInvocationEvidence.UNKNOWN_COMPLETION, firstFailure.evidence)

            awaitCondition(java.time.Duration.ofSeconds(10), "malformed-reply worker retired and replacement ready") {
                !originalChild.isAlive && broker.spawnedWorkers() == 2
            }
            competingThread.join(10_000)
            assertFalse(competingThread.isAlive)
            val competingText = requireNotNull(competingResult.get()).getOrThrow()
            val replacementChild = receipts.children.last()
            assertTrue(replacementChild.pid() != originalPid)
            assertEquals("pid=${replacementChild.pid()},calls=1,mode=valid", competingText)

            val validSecond = broker.invokeValidated(method, PortalCodec.encodeString("valid")) { bytes ->
                val reader = PortalCodec.Reader(bytes, "validated.return")
                val text = reader.string()
                reader.requireExhausted()
                text
            }
            assertEquals("pid=${replacementChild.pid()},calls=2,mode=valid", validSecond)

            val businessFailure = assertThrows(PortalRemoteCallException::class.java) {
                broker.invokeValidated(method, PortalCodec.encodeString("remote-business-error")) { bytes -> bytes.decodeToString() }
            }
            assertEquals(PortalInvocationEvidence.COMPLETED, businessFailure.evidence)
            assertTrue(replacementChild.isAlive)

            val dtoError = IllegalArgumentException("rejected by dto")
            val actualDtoError = assertThrows(IllegalArgumentException::class.java) {
                broker.invokeValidated(method, PortalCodec.encodeString("host-dto-reject")) { bytes ->
                    val reader = PortalCodec.Reader(bytes, "validated.return")
                    val text = reader.string()
                    reader.requireExhausted()
                    assertEquals("invalid-dto", text)
                    throw dtoError
                }
            }
            assertSame(dtoError, actualDtoError)
            assertTrue(replacementChild.isAlive)

            val validAfterHostError = broker.invokeValidated(method, PortalCodec.encodeString("valid")) { bytes ->
                val reader = PortalCodec.Reader(bytes, "validated.return")
                val text = reader.string()
                reader.requireExhausted()
                text
            }
            assertEquals("pid=${replacementChild.pid()},calls=5,mode=valid", validAfterHostError)
            assertEquals(2, broker.spawnedWorkers())
        }
        assertTrue(receipts.children.all { !it.isAlive })
        socketReceipts.assertAllRetired()
    }

    @Test
    fun `malformed generated reply terminally loses stateful singleton worker without replacement`(
        @TempDir directory: Path,
    ) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val fixture = validatedReplyBrokerFixture(directory, mode = PortalInstanceMode.STATEFUL_SINGLETON)
        val receipts = ChildReceipts()
        val socketReceipts = SocketReceipts()
        val method = PortalMethod.Generated(4242)
        ProcessBroker(
            launcher = receipts,
            sockets = socketReceipts,
            workerClasspath = fixture.brokerClasspath,
            instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
            workerLaunch = fixture.workerLaunch,
            limits = PortalRuntimeLimits(workers = 1, callTimeoutMs = 10_000),
        ).use { broker ->
            broker.start()
            val singletonChild = receipts.children.single()
            val singletonPid = singletonChild.pid()

            val firstValid = broker.invokeValidated(method, PortalCodec.encodeString("valid")) { bytes ->
                PortalCodec.Reader(bytes, "validated.return").let { r -> r.string().also { r.requireExhausted() } }
            }
            assertEquals("pid=$singletonPid,calls=1,mode=valid", firstValid)

            assertThrows(PortalRemoteCallException::class.java) {
                broker.invokeValidated(method, PortalCodec.encodeString("remote-business-error")) { bytes -> bytes.decodeToString() }
            }
            val dtoError = IllegalArgumentException("rejected by dto")
            val actualDtoError = assertThrows(IllegalArgumentException::class.java) {
                broker.invokeValidated(method, PortalCodec.encodeString("host-dto-reject")) { bytes ->
                    val reader = PortalCodec.Reader(bytes, "validated.return")
                    val text = reader.string()
                    reader.requireExhausted()
                    assertEquals("invalid-dto", text)
                    throw dtoError
                }
            }
            assertSame(dtoError, actualDtoError)
            assertTrue(singletonChild.isAlive)
            assertEquals(1, broker.spawnedWorkers())

            val malformed = assertThrows(PortalMalformedPayloadException::class.java) {
                broker.invokeValidated(method, PortalCodec.encodeString("malformed-bool")) { bytes ->
                    val reader = PortalCodec.Reader(bytes, "validated.return")
                    val flag = reader.boolean()
                    reader.requireExhausted()
                    flag.toString()
                }
            }
            assertEquals(PortalInvocationEvidence.UNKNOWN_COMPLETION, malformed.evidence)
            assertTrue(broker.lifecycleState() is ProcessBrokerLifecycle.Failed)
            awaitCondition(java.time.Duration.ofSeconds(5), "stateful child retired on malformed reply") {
                broker.trackedWorkers() == 0 && !singletonChild.isAlive
            }
            assertThrows(IllegalStateException::class.java) {
                broker.invokeValidated(method, PortalCodec.encodeString("valid")) { bytes -> bytes.decodeToString() }
            }
            broker.close()
            assertEquals(1, broker.spawnedWorkers())
            assertEquals(0, broker.idleSize())
        }
        assertTrue(receipts.children.all { !it.isAlive })
        socketReceipts.assertAllRetired()
    }

    @Test
    fun `fatal error and concurrent close during held reply validation retire exact child without checkout leak`(
        @TempDir directory: Path,
    ) {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val fixture = validatedReplyBrokerFixture(directory, mode = PortalInstanceMode.STATELESS)
        val method = PortalMethod.Generated(4242)

        // Fatal Error during held reply validation destroys the child and preserves cleanup suppression.
        val cleanupFailure = java.io.IOException("injected validation cleanup failure")
        val failNextClose = AtomicBoolean(false)
        val fatalSockets = SocketReceipts(failNextClose, cleanupFailure)
        val fatalReceipts = ChildReceipts()
        val fatalBroker = ProcessBroker(
            launcher = fatalReceipts,
            sockets = fatalSockets,
            workerClasspath = fixture.brokerClasspath,
            workerLaunch = fixture.workerLaunch,
        )
        fatalBroker.start()
        val fatalChild = fatalReceipts.children.single()
        val fatal = AssertionError("fatal validation failure")
        try {
            failNextClose.set(true)
            val actual = assertThrows(AssertionError::class.java) {
                fatalBroker.invokeValidated(method, PortalCodec.encodeString("valid")) { throw fatal }
            }
            assertSame(fatal, actual)
            assertFalse(fatalChild.isAlive)
            assertTrue(fatal.suppressed.contains(cleanupFailure))
            assertEquals(0, fatalBroker.idleSize())
        } finally {
            failNextClose.set(false)
            fatalBroker.close()
        }
        fatalSockets.assertAllRetired()

        // Concurrent broker close while reply validation is held leaves no checked-out or pooled child alive.
        val closeReceipts = ChildReceipts()
        val closeSockets = SocketReceipts()
        val closeBroker = ProcessBroker(
            launcher = closeReceipts,
            sockets = closeSockets,
            workerClasspath = fixture.brokerClasspath,
            workerLaunch = fixture.workerLaunch,
        )
        closeBroker.start()
        val closeChild = closeReceipts.children.single()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inValidation = CompletableFuture.supplyAsync {
            closeBroker.invokeValidated(method, PortalCodec.encodeString("valid")) { bytes ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                PortalCodec.Reader(bytes, "validated.return").let { r -> r.string().also { r.requireExhausted() } }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closeBroker.close()
            assertFalse(closeChild.isAlive)
        } finally {
            release.countDown()
            inValidation.handle { _, _ -> Unit }.get(10, TimeUnit.SECONDS)
            closeBroker.close()
        }
        assertEquals(0, closeBroker.idleSize())
        assertEquals(0, closeBroker.trackedWorkers())
        closeSockets.assertAllRetired()
    }
}
