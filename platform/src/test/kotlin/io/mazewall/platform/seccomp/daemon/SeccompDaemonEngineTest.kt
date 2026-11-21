package io.mazewall.platform.seccomp.daemon

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.SeccompConnection
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.NativeCloseFailure
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.TestFileDescriptors
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeccompDaemonEngineTest {
    @Test
    fun `unknown socket consumes capacity while remaining capacity admits another connection`() {
        val native = LinuxNative.snapshot()

        fun socket() = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("real socket", FileDescriptorRole.UnixSocket)
        val first = socket()
        val second = socket()
        val third = socket()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        val sockets = object : SocketManager by RealSocketManager.capture(native) {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                RealSocketManager.capture(native).close(fd)
                if (fd === first) throw NativeCloseFailure("unknown first close")
            }
        }
        val daemon = SeccompDaemonEngine(
            "/unused/bounded-unknown.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            maxConnections = 2,
            engine = native,
            socketManager = sockets,
            hooks = SeccompConnectionHooks(beforeListener = { fd, _ ->
                calls.incrementAndGet()
                if (fd === second) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                PreludeOutcome.Handled
            }),
        )
        val worker = Thread.ofPlatform().unstarted { daemon.handleConnection(second) }
        try {
            daemon.handleConnection(first)
            worker.start()
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            daemon.handleConnection(third)
            assertEquals(2, calls.get())
            assertTrue(third.isLiveForIo())
            kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.cleanupOutcome)
        } finally {
            release.countDown()
            if (worker.state != Thread.State.NEW) worker.join(5000)
            check(!worker.isAlive)
            listOf(first, second, third).forEach { if (it.isLiveForIo()) it.close(native.fileSystem) }
        }
    }

    @Test
    fun `accepted reused descriptor is retained and closed after unknown socket cutoff`() = reusedSocket(false)

    @Test
    fun `unknown accepted reused descriptor retains its own owner without replay`() = reusedSocket(true)

    private fun reusedSocket(acceptedUnknown: Boolean) {
        val real = LinuxNative.snapshot()
        val original = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("original socket", FileDescriptorRole.UnixSocket)
        val server = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("server socket", FileDescriptorRole.UnixSocket)
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.atomic
            .AtomicReference<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?>()
        val problem = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val originalCalls = java.util.concurrent.atomic
            .AtomicInteger()
        val acceptedCalls = java.util.concurrent.atomic
            .AtomicInteger()
        val socketManager = object : SocketManager by RealSocketManager.capture(real) {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                if (fd === original) {
                    originalCalls.incrementAndGet()
                    RealSocketManager.capture(real).close(fd)
                    throw NativeCloseFailure("actual close completed but result is unknown")
                }
                if (fd === returned.get()) {
                    acceptedCalls.incrementAndGet()
                    if (acceptedUnknown) {
                        RealSocketManager.capture(real).close(fd)
                        throw NativeCloseFailure("accepted socket close result unknown")
                    }
                }
                RealSocketManager.capture(real).close(fd)
            }
        }
        val daemon = SeccompDaemonEngine(
            "/unused/reused-socket.sock",
            notifHandlerFactory = { _, _ -> error("unexpected session") },
            engine = real,
            socketManager = socketManager,
            hooks = SeccompConnectionHooks(
                beforeListener = { _, _ -> PreludeOutcome.Handled },
                connectionAcceptor = {
                    entered.countDown()
                    check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    real.networking
                        .socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0)
                        .getFdOrThrow("new accepted socket", FileDescriptorRole.UnixSocket)
                        .also { returned.set(it) }
                },
            ),
        )
        val accept = Thread.ofPlatform().start {
            try {
                daemon.handleNewConnection(server)
            } catch (failure: Throwable) {
                problem.set(failure)
            }
        }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            daemon.handleConnection(original)
            assertEquals(1, originalCalls.get())
            daemon.triggerGlobalShutdown("explicit test cutoff")
            resume.countDown()
            accept.join(5000)
            check(!accept.isAlive)
            kotlin.test.assertNull(problem.get())
            val newer = checkNotNull(returned.get())
            assertEquals(original.value, newer.value)
            kotlin.test.assertFalse(newer.isLiveForIo())
            assertEquals(1, acceptedCalls.get())
            kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.retryCleanup())
            assertEquals(1, originalCalls.get())
            assertEquals(1, acceptedCalls.get())
            val records = SeccompDaemonEngine::class.java
                .getDeclaredField("admissions")
                .apply { isAccessible = true }
                .get(daemon) as List<*>
            val owners = records.map { record ->
                checkNotNull(record)
                .javaClass
                .getDeclaredField("socket")
                .apply { isAccessible = true }
                .get(record)
            }
            assertEquals(if (acceptedUnknown) 2 else 1, owners.size)
            assertTrue(owners.any { it === original })
            if (acceptedUnknown) assertTrue(owners.any { it === newer })
        } finally {
            resume.countDown()
            accept.join(5000)
            returned.get()?.let { if (it.isLiveForIo()) it.close(real.fileSystem) }
            if (server.isLiveForIo()) server.close(real.fileSystem)
            if (original.isLiveForIo()) original.close(real.fileSystem)
        }
    }

    @Test
    fun `shutdown retires an accepted socket whose task remained queued`() {
        val native = LinuxNative.snapshot()
        val server = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("server", FileDescriptorRole.UnixSocket)
        val accepted = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("accepted", FileDescriptorRole.UnixSocket)
        val started = java.util.concurrent.CountDownLatch(1)
        val callbacks = java.util.concurrent.atomic
            .AtomicInteger()
        val daemon = SeccompDaemonEngine(
            "/unused/queued.sock",
            notifHandlerFactory = { _, _ -> error("queued task must not run") },
            maxConnections = 1,
            engine = native,
            hooks = SeccompConnectionHooks(
                connectionAcceptor = { accepted },
                afterConnectionClosed = { _, _ -> callbacks.incrementAndGet() },
            ),
        )
        val executor = SeccompDaemonEngine::class.java
            .getDeclaredField("connectionExecutor")
            .apply { isAccessible = true }
            .get(daemon) as java.util.concurrent.ExecutorService
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            executor.execute {
                started.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            daemon.handleNewConnection(server)
            assertTrue(accepted.isLiveForIo())
            daemon.applyEvent(
                io.mazewall.platform.daemon.UnixListenDaemonEvent
                .AcceptLoopFinished(null),
            )
            kotlin.test.assertFalse(accepted.isLiveForIo())
            assertEquals(1, callbacks.get())
            assertEquals(SeccompCleanupOutcome.Complete, daemon.cleanupOutcome)
            check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            if (server.isLiveForIo()) server.close(native.fileSystem)
            if (accepted.isLiveForIo()) accepted.close(native.fileSystem)
        }
    }

    @Test
    fun `direct admitted socket retires even when public observation add throws`() {
        val native = LinuxNative.snapshot()
        val socket = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("actual socket", FileDescriptorRole.UnixSocket)
        val failure = AssertionError("public observation failure")
        val daemon = SeccompDaemonEngine("/unused/observation.sock", notifHandlerFactory = { _, _ -> error("unused") }, engine = native)
        val field = SeccompDaemonEngine::class.java.getDeclaredField("clientSockets").apply { isAccessible = true }
        field.set(
            daemon,
            object : java.util.concurrent.CopyOnWriteArrayList<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>>() {
            override fun add(element: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): Boolean = throw failure
        },
        )
        try {
            kotlin.test.assertSame(failure, kotlin.test.assertFailsWith<AssertionError> { daemon.handleConnection(socket) })
            kotlin.test.assertFalse(socket.isLiveForIo())
        } finally {
            if (socket.isLiveForIo()) socket.close(native.fileSystem)
        }
    }

    @Test
    fun `shutdown reports incomplete while a reserved accept remains in flight`() {
        val native = LinuxNative.snapshot()
        val server = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("server", FileDescriptorRole.UnixSocket)
        val accepted = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("accepted", FileDescriptorRole.UnixSocket)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val daemon = SeccompDaemonEngine(
            "/unused/inflight.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            maxConnections = 1,
            engine = native,
            hooks = SeccompConnectionHooks(connectionAcceptor = { _ ->
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                accepted
            }),
        )
        val worker = Thread.ofPlatform().start {
            try {
            daemon.handleNewConnection(server)
        } catch (failure: Throwable) {
            error.set(failure)
        }
        }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            daemon.triggerGlobalShutdown("test")
            kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.cleanupOutcome)
            assertTrue(accepted.isLiveForIo())
            release.countDown()
            worker.join(5000)
            check(!worker.isAlive)
            kotlin.test.assertNull(error.get())
            kotlin.test.assertFalse(accepted.isLiveForIo())
            assertEquals(SeccompCleanupOutcome.Complete, daemon.cleanupOutcome)
        } finally {
            release.countDown()
            worker.join(5000)
            if (server.isLiveForIo()) server.close(native.fileSystem)
            if (accepted.isLiveForIo()) accepted.close(native.fileSystem)
        }
    }

    @Test
    fun `inflight accept reserves capacity before hook and direct admission`() {
        val native = LinuxNative.snapshot()
        val server = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("server socket", FileDescriptorRole.UnixSocket)
        val accepted = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("accepted socket", FileDescriptorRole.UnixSocket)
        val direct = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("direct socket", FileDescriptorRole.UnixSocket)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val retired = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        val error = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val daemon = SeccompDaemonEngine(
            "/unused/accept-bound.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            maxConnections = 1,
            engine = native,
            hooks = SeccompConnectionHooks(
                connectionAcceptor = { _ ->
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                accepted
            },
                beforeListener = { _, _ -> PreludeOutcome.Handled },
                afterConnectionClosed = { _, _ -> retired.countDown() },
            ),
        )
        val acceptThread = Thread.ofPlatform().start {
            try {
            daemon.handleNewConnection(server)
        } catch (failure: Throwable) {
            error.set(failure)
        }
        }
        val executor = SeccompDaemonEngine::class.java
            .getDeclaredField("connectionExecutor")
            .apply { isAccessible = true }
            .get(daemon) as java.util.concurrent.ExecutorService
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            daemon.handleNewConnection(server)
            daemon.handleConnection(direct)
            assertEquals(1, calls.get())
            assertTrue(direct.isLiveForIo())
            release.countDown()
            acceptThread.join(5000)
            check(!acceptThread.isAlive)
            kotlin.test.assertNull(error.get())
            check(retired.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            acceptThread.join(5000)
            executor.shutdown()
            check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            if (server.isLiveForIo()) server.close(native.fileSystem)
            if (accepted.isLiveForIo()) accepted.close(native.fileSystem)
            if (direct.isLiveForIo()) direct.close(native.fileSystem)
        }
    }

    @Test
    fun `duplicate direct socket never closes original active descriptor`() {
        val native = LinuxNative.snapshot()
        val socket = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("actual socket", FileDescriptorRole.UnixSocket)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        val daemon = SeccompDaemonEngine(
            "/unused/duplicate.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            maxConnections = 2,
            engine = native,
            hooks = SeccompConnectionHooks(beforeListener = {
                _,
                    _,
                ->
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                PreludeOutcome.Handled
            }),
        )
        val worker = Thread.ofPlatform().start { daemon.handleConnection(socket) }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            daemon.handleConnection(socket)
            val records = SeccompDaemonEngine::class.java
                .getDeclaredField("admissions")
                .apply { isAccessible = true }
                .get(daemon) as List<*>
            val record = checkNotNull(records.single())
            val actionClass = Class.forName("io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine\$QueuedAction")
            val action = actionClass.enumConstants.single { (it as Enum<*>).name == "RUN" }
            val invoke = SeccompDaemonEngine::class.java.getDeclaredMethod("performQueuedAdmission", record.javaClass, FileDescriptor::class.java, actionClass).apply { isAccessible = true }
            val forbidden = kotlin.test.assertFailsWith<java.lang.reflect.InvocationTargetException> { invoke.invoke(daemon, record, socket, action) }
            kotlin.test.assertIs<IllegalStateException>(forbidden.cause)
            val forged = record.javaClass
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            val rejected = kotlin.test.assertFailsWith<java.lang.reflect.InvocationTargetException> { invoke.invoke(daemon, forged, socket, action) }
            kotlin.test.assertIs<IllegalStateException>(rejected.cause)
            assertEquals(1, calls.get())
            assertTrue(socket.isLiveForIo())
        } finally {
            release.countDown()
            worker.join(5000)
            check(!worker.isAlive)
            if (socket.isLiveForIo()) socket.close(native.fileSystem)
        }
    }

    @Test
    fun `pending eventfd retains original session until actual user exits and explicit retry`() {
        val real = LinuxNative.snapshot()
        val socket = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("actual socket", FileDescriptorRole.UnixSocket)
        val other = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("other socket", FileDescriptorRole.UnixSocket)
        val listener = NativeArena.ofConfined().use { arena ->
            real.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY).getFdOrThrow("actual mock listener", FileDescriptorRole.SeccompNotif)
        }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workerFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val closeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        val retryResult = java.util.concurrent.atomic
            .AtomicReference<SeccompCleanupOutcome?>()
        val retryFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        var worker: Thread? = null
        var polls = 0
        var eventfdCloses = 0
        lateinit var daemon: SeccompDaemonEngine
        val engine = object : NativeEngine by real {
            override val memory = object : NativeMemory by real.memory {
                override fun write(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd.role == FileDescriptorRole.Generic) {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return real.memory.write(fd, buf, count)
                }
            }
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd.role == FileDescriptorRole.Generic) {
                        eventfdCloses++
                        closeEntered.countDown()
                        check(releaseClose.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return real.fileSystem.close(fd)
                }
            }
            override val raw = object : RawSyscallOperations by real.raw {
                override fun poll(
                    fds: ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (++polls <= 2) return LinuxNative.SyscallResult.Success(1L)
                    val records = SeccompDaemonEngine::class.java
                        .getDeclaredField("admissions")
                        .apply { isAccessible = true }
                        .get(daemon) as List<*>
                    val record = checkNotNull(records.single())
                    val session = record.javaClass
                        .getDeclaredField("session")
                        .apply { isAccessible = true }
                        .get(record) as SeccompSessionHandler
                    val wakeup = SeccompSessionHandler::class.java
                        .getDeclaredField("completionWakeup")
                        .apply { isAccessible = true }
                        .get(session) as SessionCompletionWakeup
                    worker = Thread.ofPlatform().start {
                        try {
                        wakeup.signal()
                    } catch (failure: Throwable) {
                        workerFailure.set(failure)
                    }
                    }
                    check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
                }
            }
        }
        val sockets = object : SocketManager by RealSocketManager.capture(real) {
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = listener
        }
        daemon = SeccompDaemonEngine("/unused/pending.sock", notifHandlerFactory = { _, _ ->
            object : SeccompNotifHandler {
            context(arena: NativeArena) override fun beginNotification(
                notif: ManagedSegment,
                resp: ManagedSegment,
                listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                deadline: Deadline,
            ): NotificationWork = error("unused")
        }
        }, maxConnections = 1, engine = engine, socketManager = sockets, handshakeWriter = { _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L) })
        try {
            daemon.handleConnection(socket)
            kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.cleanupOutcome)
            assertEquals(0, eventfdCloses)
            daemon.handleConnection(other)
            assertTrue(other.isLiveForIo())
            release.countDown()
            checkNotNull(worker).join(5000)
            check(!checkNotNull(worker).isAlive)
            kotlin.test.assertNull(workerFailure.get())
            val retry = Thread.ofPlatform().start {
                try {
                retryResult.set(daemon.retryCleanup())
            } catch (failure: Throwable) {
                retryFailure.set(failure)
            }
            }
            try {
                check(closeEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.retryCleanup())
                assertEquals(1, eventfdCloses)
            } finally {
                releaseClose.countDown()
                retry.join(5000)
            }
            check(!retry.isAlive)
            kotlin.test.assertNull(retryFailure.get())
            assertEquals(SeccompCleanupOutcome.Complete, retryResult.get())
            assertEquals(1, eventfdCloses)
        } finally {
            release.countDown()
            releaseClose.countDown()
            worker?.join(5000)
            if (socket.isLiveForIo()) socket.close(real.fileSystem)
            if (listener.isLiveForIo()) listener.close(real.fileSystem)
            if (other.isLiveForIo()) other.close(real.fileSystem)
        }
    }

    @Test
    fun `unknown real eventfd retirement retains daemon owner and cuts off admission`() = exerciseUnknownEventfd(false)

    @Test
    fun `fatal real eventfd retirement publishes cutoff and retains exact failure`() = exerciseUnknownEventfd(true)

    private fun exerciseUnknownEventfd(fatal: Boolean) {
        val real = LinuxNative.snapshot()
        val socket = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("actual socket", FileDescriptorRole.UnixSocket)
        val later = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("later socket", FileDescriptorRole.UnixSocket)
        val server = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("server socket", FileDescriptorRole.UnixSocket)
        val accepted = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("accepted socket", FileDescriptorRole.UnixSocket)
        val acceptEntered = java.util.concurrent.CountDownLatch(1)
        val releaseAccept = java.util.concurrent.CountDownLatch(1)
        val acceptFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val replacements = mutableListOf<EventFdCompletionWakeup>()
        val listener = NativeArena.ofConfined().use { arena ->
            real.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY).getFdOrThrow("actual mock listener", FileDescriptorRole.SeccompNotif)
        }
        val failure: Throwable = if (fatal) AssertionError("fatal after actual eventfd close") else IllegalStateException("reported after actual eventfd close")
        val attempts = java.util.concurrent.atomic
            .AtomicInteger()
        var polls = 0
        val engine = object : NativeEngine by real {
            override val raw = object : RawSyscallOperations by real.raw {
                override fun poll(
                    fds: ManagedSegment,
                    nfds: Long,
                    timeout: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    if (++polls <= 2) LinuxNative.SyscallResult.Success(1L) else LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L)
            }
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd.role == FileDescriptorRole.Generic) {
                        attempts.incrementAndGet()
                        real.fileSystem.close(fd).getOrThrow("actual eventfd close")
                        throw failure
                    }
                    return real.fileSystem.close(fd)
                }
            }
        }
        val acceptedAttempts = java.util.concurrent.atomic
            .AtomicInteger()
        val sockets = object : SocketManager by RealSocketManager.capture(real) {
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = listener

            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                if (fd === accepted && acceptedAttempts.incrementAndGet() == 1) throw IllegalStateException("before accepted close")
                RealSocketManager.capture(real).close(fd)
            }
        }
        var factoryCalls = 0
        val daemon = SeccompDaemonEngine(
            "/unused/unknown.sock",
            notifHandlerFactory = { _, _ ->
            factoryCalls++
            object : SeccompNotifHandler {
                context(arena: NativeArena) override fun beginNotification(
                    notif: ManagedSegment,
                    resp: ManagedSegment,
                    listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                    socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                    deadline: Deadline,
                ): NotificationWork = error("unused")
            }
        },
            engine = engine,
            socketManager = sockets,
            handshakeWriter = { _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L) },
            hooks = SeccompConnectionHooks(connectionAcceptor = { _ ->
                acceptEntered.countDown()
                check(releaseAccept.await(5, java.util.concurrent.TimeUnit.SECONDS))
                accepted
            }),
        )
        val acceptThread = Thread.ofPlatform().start {
            try {
            daemon.handleNewConnection(server)
        } catch (problem: Throwable) {
            acceptFailure.set(problem)
        }
        }
        try {
            check(acceptEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            if (fatal) {
                kotlin.test.assertSame(failure, kotlin.test.assertFailsWith<AssertionError> { daemon.handleConnection(socket) })
            } else {
                daemon.handleConnection(socket)
            }
            releaseAccept.countDown()
            acceptThread.join(5000)
            check(!acceptThread.isAlive)
            kotlin.test.assertNull(acceptFailure.get())
            assertTrue(accepted.isLiveForIo())
            assertEquals(1, acceptedAttempts.get())
            assertEquals(1, attempts.get())
            val records = SeccompDaemonEngine::class.java
                .getDeclaredField("admissions")
                .apply { isAccessible = true }
                .get(daemon) as List<*>
            val record = checkNotNull(
                records.firstOrNull { candidate ->
                candidate != null &&
                    candidate.javaClass
                    .getDeclaredField("session")
                    .apply { isAccessible = true }
                    .get(candidate) != null
            },
            )
            val session = record.javaClass
                .getDeclaredField("session")
                .apply { isAccessible = true }
                .get(record) as SeccompSessionHandler
            val oldWakeup = SeccompSessionHandler::class.java
                .getDeclaredField("completionWakeup")
                .apply { isAccessible = true }
                .get(session) as SessionCompletionWakeup
            repeat(64) { if (replacements.none { it.pollFd == oldWakeup.pollFd }) replacements.add(EventFdCompletionWakeup.create(real)) }
            val replacement = checkNotNull(replacements.firstOrNull { it.pollFd == oldWakeup.pollFd })
            kotlin.test.assertSame(failure, kotlin.test.assertIs<SessionRetirement.Unknown>(session.retireCompletion()).failure)
            assertEquals(1, attempts.get())
            val outcome = kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.cleanupOutcome)
            assertTrue(outcome.failures.any { it === failure || it.cause === failure })
            assertEquals(listOf(accepted), outcome.retryableOwnership)
            daemon.retryCleanup()
            assertEquals(2, acceptedAttempts.get())
            kotlin.test.assertFalse(accepted.isLiveForIo())
            assertEquals(1, attempts.get())
            replacement.signal()
            replacement.drain()
            kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.cleanupOutcome)
            daemon.handleConnection(later)
            assertEquals(1, factoryCalls)
            assertTrue(later.isLiveForIo())
        } finally {
            releaseAccept.countDown()
            acceptThread.join(5000)
            replacements.forEach { it.close() }
            if (server.isLiveForIo()) server.close(real.fileSystem)
            if (accepted.isLiveForIo()) accepted.close(real.fileSystem)
            if (socket.isLiveForIo()) socket.close(real.fileSystem)
            if (listener.isLiveForIo()) listener.close(real.fileSystem)
            if (later.isLiveForIo()) later.close(real.fileSystem)
        }
    }

    @Test
    fun `direct admission at capacity leaves caller owned socket untouched`() {
        val native = LinuxNative.snapshot()
        val first = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("first socket", FileDescriptorRole.UnixSocket)
        val second = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("second socket", FileDescriptorRole.UnixSocket)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        val daemon = SeccompDaemonEngine(
            "/unused/capacity.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            maxConnections = 1,
            engine = native,
            hooks = SeccompConnectionHooks(beforeListener = { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                PreludeOutcome.Handled
            }),
        )
        val worker = Thread.ofPlatform().start { daemon.handleConnection(first) }
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            daemon.clientSockets.clear() // This compatibility list is not admission authority.
            daemon.handleConnection(second)
            assertEquals(1, calls.get())
            assertTrue(second.isLiveForIo())
        } finally {
            release.countDown()
            worker.join(5000)
            check(!worker.isAlive)
            if (first.isLiveForIo()) first.close(native.fileSystem)
            if (second.isLiveForIo()) second.close(native.fileSystem)
        }
    }

    @Test
    fun `handled prelude never receives listener and closes its accepted socket`() = preludeCase(PreludeOutcome.Handled, null)

    @Test
    fun `legacy prelude proceeds to listener receive before closing its accepted socket`() = preludeCase(PreludeOutcome.LegacyListener, null)

    @Test
    fun `prelude failure retains accepted socket cleanup and original fatal identity`() {
        for (failure in listOf(java.io.IOException("prelude failure"), LinkageError("prelude fatal"), java.util.concurrent.CancellationException("prelude cancel"))) {
            preludeCase(PreludeOutcome.Handled, failure)
        }
    }

    @Test
    fun `targeted connection cleanup retries the original failed owner without closing another live socket`() {
        val native = LinuxNative.snapshot()
        val failed = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("failed close socket", FileDescriptorRole.UnixSocket)
        val other = native.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("unrelated live socket", FileDescriptorRole.UnixSocket)
        val failure = java.io.IOException("controlled pre-dispatch close")
        var attempts = 0
        val published = mutableListOf<SeccompCleanupOutcome>()
        val sockets = object : SocketManager by RealSocketManager.capture(native) {
            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                kotlin.test.assertSame(failed, fd)
                if (++attempts == 1) throw failure
                native.fileSystem.close(fd).getOrThrow("retry original socket close")
            }
        }
        val daemon = SeccompDaemonEngine(
            "/unused/targeted-cleanup.sock",
            notifHandlerFactory = { _, _ -> error("No notification listener") },
            engine = native,
            socketManager = sockets,
            hooks = SeccompConnectionHooks(
                beforeListener = { _, _ -> PreludeOutcome.Handled },
                afterConnectionClosed = { socket, outcome ->
                    kotlin.test.assertSame(failed, socket)
                    published += outcome
                },
            ),
        )
        try {
            daemon.clientSockets.add(other)
            daemon.handleConnection(failed)
            val incomplete = kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(published.single())
            kotlin.test.assertSame(failure, incomplete.failures.single())
            assertTrue(failed.isLiveForIo())
            daemon.retryConnectionCleanup(failed)
            assertEquals(listOf(published.first(), SeccompCleanupOutcome.Complete), published)
            kotlin.test.assertFalse(failed.isLiveForIo())
            assertTrue(other.isLiveForIo())
            daemon.retryConnectionCleanup(failed)
            assertEquals(2, attempts)
            val historical = kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(daemon.cleanupOutcome)
            assertTrue(historical.failures.contains(failure))
        } finally {
            if (failed.isLiveForIo()) failed.close(native.fileSystem)
            if (other.isLiveForIo()) other.close(native.fileSystem)
        }
    }

    @Test
    fun `connection release observer receives failed close outcome before cancellation or fatal propagation`() {
        for (failure in listOf(NativeCloseFailure("close failure"), LinkageError("close fatal"), java.util.concurrent.CancellationException("close cancel"))) {
            preludeCase(PreludeOutcome.Handled, null, failure)
        }
    }

    private fun preludeCase(
        outcome: PreludeOutcome,
        failure: Throwable?,
        closeFailure: Throwable? = null,
    ) {
        val real = LinuxNative.snapshot()
        val socket = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("prelude fixture socket", FileDescriptorRole.UnixSocket)
        val events = mutableListOf<String>()
        val sockets = object : SocketManager by RealSocketManager.capture(real) {
            override fun recvDescriptor(
                socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>,
            ): FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>? {
                events += "listener"
                return null
            }

            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                events += "close"
                if (closeFailure != null && closeFailure !is NativeCloseFailure) throw closeFailure
                real.fileSystem.close(fd).getOrThrow("close prelude fixture")
                if (closeFailure != null) throw closeFailure
            }
        }
        val native = MockNativeEngine().apply {
            onPoll = { _, _, _ -> LinuxNative.SyscallResult.Success(1L) }
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/unused-prelude.sock",
            notifHandlerFactory = { _, _ -> error("No listener was transferred") },
            engine = native,
            socketManager = sockets,
            hooks = SeccompConnectionHooks(
                beforeListener = { actual, deadline ->
                    kotlin.test.assertSame(socket, actual)
                    assertTrue(socket.isLiveForIo())
                    assertTrue(deadline.remainingMillis() in 1..5000)
                    events += "prelude"
                    if (failure != null) throw failure
                    outcome
                },
                afterConnectionClosed = { actual, cleanup ->
                    kotlin.test.assertSame(socket, actual)
                    val retained = closeFailure != null && closeFailure !is NativeCloseFailure
                    assertEquals(retained, socket.isLiveForIo())
                    if (closeFailure == null) {
                        assertEquals(SeccompCleanupOutcome.Complete, cleanup)
                    } else {
                        val incomplete = kotlin.test.assertIs<SeccompCleanupOutcome.Incomplete>(cleanup)
                        assertTrue(incomplete.failures.any { it === closeFailure || it.cause === closeFailure })
                        assertEquals(if (retained) listOf(socket) else emptyList(), incomplete.retryableOwnership)
                    }
                    events += "released"
                },
            ),
        )
        daemon.clientSockets += socket
        try {
            val propagated = closeFailure ?: failure
            if (propagated is Error || propagated is java.util.concurrent.CancellationException) {
                kotlin.test.assertSame(propagated, kotlin.test.assertFails { daemon.handleConnection(socket) })
            } else {
                daemon.handleConnection(socket)
            }
            val expected = if (failure == null && outcome == PreludeOutcome.LegacyListener) listOf("prelude", "listener", "close", "released") else listOf("prelude", "close", "released")
            assertEquals(expected, events)
            val retained = closeFailure != null && closeFailure !is NativeCloseFailure
            assertEquals(retained, socket.isLiveForIo())
            assertEquals(if (retained) listOf(socket) else emptyList(), daemon.clientSockets.toList())
        } finally {
            if (socket.isLiveForIo()) real.fileSystem.close(socket).getOrThrow("retire prelude fixture")
        }
    }

    @Test
    fun `accepted control socket is requested nonblocking`() {
        var acceptedFlags = 0
        val engine = MockNativeEngine().apply {
            networking.onAccept4 = { _, _, _, flags ->
                acceptedFlags = flags
                LinuxNative.SyscallResult.Error(NativeConstants.EAGAIN, -1L)
            }
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = engine,
        )

        daemon.handleNewConnection(TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(9))

        assertTrue((acceptedFlags and NativeConstants.O_NONBLOCK.toInt()) != 0)
    }

    @Test
    fun `handshake acknowledgement makes one attempt after each poll`() {
        var writes = 0
        val nativeEngine = MockNativeEngine()
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            engine = nativeEngine,
            handshakeWriter = { _, _, _ ->
                writes++
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(NativeConstants.EINTR, -1L)
            },
        )
        val socketFd = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10)

        NativeArena.ofConfined().use { arena ->
            val result = daemon.writeHandshakeAck(
                socketFd,
                arena.allocate(1),
                Deadline.afterMillis(10_000),
            )
            assertEquals(HandshakeWriteOutcome.RetryAfterPoll, result)
        }
        assertEquals(1, writes)
    }

    @Test
    fun `short handshake acknowledgement fails closed`() {
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            handshakeWriter = { _, _, _ ->
                LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(0L)
            },
        )
        NativeArena.ofConfined().use { arena ->
            assertEquals(
                HandshakeWriteOutcome.Failed(NativeConstants.EIO),
                daemon.writeHandshakeAck(
                    TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10),
                    arena.allocate(1),
                    Deadline.afterMillis(10_000),
                ),
            )
        }
    }

    @Test
    fun `handshake deadlines distinguish connections that reuse an fd number`() {
        val deadlines = HandshakeDeadlineRegistry()
        val socket = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10)
        val first = SeccompConnection.Accepted(socket)
        val second = SeccompConnection.Accepted(socket)

        val firstDeadline = deadlines.getOrCreate(first) { Deadline(100) }
        val secondDeadline = deadlines.getOrCreate(second) { Deadline(200) }
        deadlines.remove(first)

        assertEquals(100, firstDeadline.nanoTime)
        assertEquals(200, secondDeadline.nanoTime)
        assertEquals(1, deadlines.size)
    }

    @Test
    fun `expired handshake acknowledgement does not invoke writer`() {
        var writes = 0
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ -> error("unused") },
            handshakeWriter = { _, _, _ ->
                writes++
                LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1)
            },
        )
        val socketFd = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(10)

        NativeArena.ofConfined().use { arena ->
            val result = daemon.writeHandshakeAck(
                socketFd,
                arena.allocate(1),
                Deadline(System.nanoTime() - 1),
            )
            assertEquals(HandshakeWriteOutcome.TimedOut, result)
        }
        assertEquals(0, writes)
    }

    @Test
    fun `session reactor closes factory-created notification handler`() {
        var notificationHandlerClosed = false
        val real = LinuxNative.snapshot()
        val socket = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("actual socket", FileDescriptorRole.UnixSocket)
        val listener = NativeArena.ofConfined().use { arena ->
            real.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY).getFdOrThrow("actual mock listener", FileDescriptorRole.SeccompNotif)
        }
        var polls = 0
        val nativeEngine = MockNativeEngine().apply {
            onPoll = { _, _, _ -> if (++polls <= 2) LinuxNative.SyscallResult.Success(1L) else LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L) }
        }
        val sockets = object : SocketManager by RealSocketManager.capture(real) {
            override fun recvDescriptor(socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership>) = listener
        }
        val daemon = SeccompDaemonEngine(
            socketPath = "/tmp/test.sock",
            notifHandlerFactory = { _, _ ->
                object : SeccompNotifHandler, AutoCloseable {
                    context(arena: NativeArena) override fun beginNotification(
                        notif: ManagedSegment,
                        resp: ManagedSegment,
                        listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                        socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                        deadline: Deadline,
                    ): NotificationWork = error("no notification expected")

                    override fun close() {
                        notificationHandlerClosed = true
                    }
                }
            },
            engine = nativeEngine,
            socketManager = sockets,
            handshakeWriter = { _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(1L) },
        )
        try {
            daemon.handleConnection(socket)
            assertTrue(notificationHandlerClosed)
            kotlin.test.assertFalse(socket.isLiveForIo())
            kotlin.test.assertFalse(listener.isLiveForIo())
        } finally {
            if (socket.isLiveForIo()) socket.close(real.fileSystem)
            if (listener.isLiveForIo()) listener.close(real.fileSystem)
        }
    }

    @Test
    fun `unadmitted active step refuses before session and leaves caller socket open`() {
        val real = LinuxNative.snapshot()
        val socket = real.networking.socket(1, 1 or NativeConstants.SOCK_CLOEXEC, 0).getFdOrThrow("actual socket", FileDescriptorRole.UnixSocket)
        val listener = NativeArena.ofConfined().use { arena ->
            real.fileSystem.open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY).getFdOrThrow("actual mock listener", FileDescriptorRole.SeccompNotif)
        }
        var factories = 0
        val daemon = SeccompDaemonEngine("/unused/refuse.sock", notifHandlerFactory = { _, _ ->
            factories++
            error("must not run")
        }, engine = MockNativeEngine())
        try {
            val connection = SeccompConnection.Active(socket, listener)
            NativeArena.ofConfined().use { arena -> kotlin.test.assertFailsWith<IllegalStateException> { daemon.processConnectionStep(arena, connection, socket, arena.allocate(8L)) } }
            assertEquals(0, factories)
            assertTrue(socket.isLiveForIo())
            assertTrue(listener.isLiveForIo())
        } finally {
            socket.close(real.fileSystem)
            listener.close(real.fileSystem)
        }
    }
}
