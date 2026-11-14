package io.mazewall.ffi.networking

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.MockProcess
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.supervisor.ConfinedBinding
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperControl
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperJob
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperPreparation
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProcessIo
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ControlTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ResultTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.RootTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperRejection
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperSession
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData
import io.mazewall.enforcer.internal.supervisor.ConfinedManifest
import io.mazewall.enforcer.internal.supervisor.ConfinedReadRequest
import io.mazewall.enforcer.internal.supervisor.ConfinedRejectReason
import io.mazewall.enforcer.internal.supervisor.PoolProvisioning
import io.mazewall.enforcer.internal.supervisor.ProcessIdentity
import io.mazewall.enforcer.internal.supervisor.SocketHelper
import io.mazewall.enforcer.internal.supervisor.SocketHelperPool
import io.mazewall.enforcer.internal.supervisor.SocketHelperProcess
import io.mazewall.enforcer.internal.supervisor.SocketHelperResult
import io.mazewall.enforcer.internal.supervisor.SocketOperation
import io.mazewall.enforcer.internal.supervisor.StopReason
import io.mazewall.enforcer.internal.supervisor.SupervisorContext
import io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.internal.duplicateHelperResource
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.fd.close
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.seccomp.daemon.PreludeOutcome
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.platform.seccomp.daemon.SeccompDaemonEngine
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfinedHelperControlTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `parent connect retries only transient backlog failures within its unchanged cap`() {
        for (errno in listOf(4, 11)) {
            ConfinedHelperChannelTest.Fixture(85100 + errno.toLong()).use { f ->
                var connects = 0
                f.native.networking.onConnect = { socket, _, _ ->
                    kotlin.test.assertSame(f.socket, socket)
                    connects++
                    LinuxNative.SyscallResult.Error(errno, -1L)
                }
                f.native.onPoll = { _, count, timeout ->
                    assertEquals(0L, count, "Backlog retry must not poll disconnected socket readiness")
                    assertTrue(timeout in 1..25)
                    f.now += timeout * 1_000_000L
                    LinuxNative.SyscallResult.Success(0L)
                }
                assertFailsWith<TimeoutException> { f.channel.connect("/unused/control.sock", ConfinedDeadline(1_075_000_000)) }
                assertEquals(3, connects)
                assertEquals(1_075_000_000, f.now)
                assertTrue(f.socket.isLiveForIo(), "The acquiring session still owns failed connection cleanup")
                assertFails { f.channel.connect("/unused/control.sock", ConfinedDeadline(2_000_000_000)) }
                assertEquals(3, connects)
            }
        }
    }

    @Test
    fun `parent connect preserves permission failure at expiry and refuses cancelled or invalid input`() {
        for (errno in listOf(1, 13)) {
            ConfinedHelperChannelTest.Fixture(85200 + errno.toLong()).use { f ->
                var connects = 0
                f.native.networking.onConnect = { _, _, _ ->
                    connects++
                    f.now = 2_000_000_001
                    LinuxNative.SyscallResult.Error(errno, -1L)
                }
                val failure = assertFailsWith<ConfinedHelperRejection> { f.channel.connect("/unused/control.sock", ConfinedDeadline(2_000_000_000)) }
                assertEquals(ConfinedRejectReason.Permission, failure.reason)
                assertEquals(errno, failure.errno)
                assertEquals(1, connects)
                assertTrue(f.socket.isLiveForIo())
            }
        }
        for (path in listOf("", "/bad\u0000suffix", "/" + "é".repeat(54))) {
            ConfinedHelperChannelTest.Fixture(85300).use { f ->
                f.native.networking.onConnect = { _, _, _ -> error("Invalid path reached native connect") }
                assertFailsWith<IllegalArgumentException> { f.channel.connect(path, ConfinedDeadline(2_000_000_000)) }
            }
        }
        ConfinedHelperChannelTest.Fixture(85301).use { f ->
            f.cancelled = true
            f.native.networking.onConnect = { _, _, _ -> error("Cancelled connect reached native code") }
            assertFailsWith<java.util.concurrent.CancellationException> { f.channel.connect("/unused/control.sock", ConfinedDeadline(2_000_000_000)) }
        }
    }

    @Test
    fun `parent bounded connect reaches actual unix endpoint with its original owned socket`() {
        val native = LinuxNative.snapshot()
        val path = directory.resolve("bounded.sock")
        java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { server ->
            server.bind(java.net.UnixDomainSocketAddress.of(path))
            val socket = native.networking.socket(1, 1 or 0x80000 or 0x800, 0).getFdOrThrow("bounded connection", FileDescriptorRole.UnixSocket)
            SupervisorOwnedFd(socket, native.fileSystem).use {
                val channel = ConfinedHelperChannel(native, socket, { false }, { true })
                val deadline = ConfinedDeadline.mint(native) { 1_000_000_000 }
                channel.connect(path.toString(), deadline)
                assertTrue(native.raw.fcntl(socket, 3, 0).helperValue("connection flags") and 0x800 != 0L)
                channel.send(byteArrayOf(0xe4.toByte()), deadline)
                server.accept().use { peer ->
                    val byte = java.nio.ByteBuffer.allocate(1)
                    assertEquals(1, peer.read(byte))
                    assertEquals(0xe4.toByte(), byte.array().single())
                }
            }
        }
    }

    /** Real channels and stage interpreters; only the helper process/confinement effects are synthetic. */
    internal class RelayFixture(
        private val directory: Path,
        controlObservations: HelperSecurityObservation = HelperSecurityObservation(),
        decorateNative: (NativeEngine) -> NativeEngine = { it },
        beforeForwarded: () -> Unit = {},
        afterForwarded: () -> Unit = {},
        sharedObservations: HelperSecurityObservation? = null,
    ) : AutoCloseable {
        val native = decorateNative(LinuxNative.snapshot())
        val observations = sharedObservations ?: HelperSecurityObservation()
        val controlObservationsUsed = sharedObservations ?: controlObservations
        private val pid = Pid(ProcessHandle.current().pid().toInt())
        val bootstrap = observations.observeAncestry(pid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
        val roots = listOf(
            PreparedPath.open(Files.createDirectory(directory.resolve("application")), native),
            PreparedPath.open(Files.writeString(directory.resolve("support.jar"), "reviewed"), native),
        )
        private val policy = CanonicalPolicyArtifact.worker(
            RuntimeProfile.HOTSPOT_JIT,
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(roots[0].requested)
                .allowFsRead(roots[1].requested)
                .build(),
        )
        private val resources = PreparedResourceSet.capture(policy.definition, native)

        private fun digest(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        val plan = PreparedHelperReadPlan.capture(
            policy,
            resources,
            HelperBinding(pid, bootstrap.identity.startTimeTicks, 13, resources.generation, digest(policy.toJson().toByteArray())),
            listOf(ReviewedHelperSupport(roots[1].requested, digest("reviewed".toByteArray()))),
            HelperSupportLifetime.NoWriters,
            native,
            bootstrap.ancestry,
        )
        val manifest = plan.manifest
        val child = ProcessIdentity(Pid(7001), 91)
        val reaps = AtomicInteger()
        val failReap = AtomicBoolean()
        val reapFailure = IOException("controlled retained helper reap failure")
        var beforeReap: () -> Unit = {}
        val retries = AtomicInteger()
        private val helperSockets = pair()
        val helper = ConfinedHelperChannel(native, helperSockets.second, { false }, { true })
        val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
            object : SocketHelper {
                override val confinedIdentity = child

                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = error("Legacy work")

                override fun runConfined(exchange: ConfinedHelperJob) {
                    val channel = ConfinedHelperChannel(native, helperSockets.first, { exchange.cancelled }, { true })
                    val io = ConfinedHelperProcessIo(channel, exchange, {}, { capability, owner ->
                        assertEquals(capability.identity, observations.observeRoot(owner.handle))
                    }, { _, owner -> observations.observeReadable(owner.handle, native) })
                    while (!exchange.cancelled) {
                        exchange.checkActive(ConfinedDeadline.now(native))
                        val ticket = exchange.poll(ConfinedDeadline.now(native))
                        if (ticket != null) {
                            if (ticket.command is ConfinedHelperJob.Command.ResultForwarded) beforeForwarded()
                            io.execute(ticket)
                            if (ticket.command is ConfinedHelperJob.Command.ResultForwarded) afterForwarded()
                        } else {
                            check(channel.pollFrame(exchange.operationDeadline) == null)
                        }
                    }
                }

                override fun close() {
                    reaps.incrementAndGet()
                    beforeReap()
                    if (failReap.get()) throw reapFailure
                    helperSockets.first.close(native.fileSystem)
                }
            }
        }
        val control: ConfinedHelperControl
        private val connections = java.util.concurrent.CopyOnWriteArrayList<Connection>()
        private var retryEndpoint: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) -> Unit = { error("No endpoint") }

        init {
            pool.prewarm()
            control = ConfinedHelperControl(pool, SocketHelperProcess.ConfinedStartup(bootstrap, bootstrap), native, controlObservationsUsed, retryConnection = { socket ->
                retries.incrementAndGet()
                val old = connections.firstOrNull { it.accepted === socket }
                if (old == null) {
                    retryEndpoint(socket)
                } else {
                    assertTrue(old.loopEnded.isDone)
                    old.closeAllowed.countDown()
                }
            })
        }

        private fun pair() =
            NativeArena.ofConfined().use { arena ->
            val pair = native.networking.socketpair(1, 1 or 0x80000, 0, arena.allocate(8))
            pair.getFdOrThrow("control test socketpair", FileDescriptorRole.UnixSocket) to pair.getFdOrThrow("control test socketpair", FileDescriptorRole.UnixSocket)
        }

        inner class Connection(
            holdClose: Boolean,
        ) : AutoCloseable {
            private val sockets = pair()
            val accepted = sockets.first
            val parent = ConfinedHelperChannel(native, sockets.second, { false }, { true })
            val closeAllowed = CountDownLatch(if (holdClose) 1 else 0)
            val loopEnded = CompletableFuture<PreludeOutcome>()
            val closed = CompletableFuture<Unit>()
            private val thread = Thread.ofPlatform().name("confined-control-fixture").start {
                try {
                    loopEnded.complete(control.handle(accepted, Deadline.afterMillis(5000)))
                } catch (failure: Throwable) {
                    loopEnded.completeExceptionally(failure)
                } finally {
                    try {
                        check(closeAllowed.await(10, TimeUnit.SECONDS))
                        accepted.close(native.fileSystem)
                        control.connectionClosed(accepted, SeccompCleanupOutcome.Complete)
                        closed.complete(Unit)
                    } catch (failure: Throwable) {
                        closed.completeExceptionally(failure)
                    }
                }
            }

            fun start(frame: Frame) {
                parent.send(byteArrayOf(0xe4.toByte()), frame.deadline)
                parent.sendFrame(frame)
            }

            override fun close() {
                closeAllowed.countDown()
                if (sockets.second.isLiveForIo()) sockets.second.close(native.fileSystem)
                thread.join(10_000)
                check(!thread.isAlive)
                closed.get(1, TimeUnit.SECONDS)
            }
        }

        fun connect(holdClose: Boolean = false) = Connection(holdClose).also(connections::add)

        fun loseHelper() = helperSockets.second.close(native.fileSystem)

        inner class Endpoint(
            retirementReply: ((Frame.Control) -> Frame)? = null,
        ) : AutoCloseable {
            val firstAccepted = CompletableFuture<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>>()
            val firstCleanup = CompletableFuture<SeccompCleanupOutcome>()
            var afterSocketClose: (FileDescriptor<*, FdState.Open, FdOwnership.Owned>) -> Unit = {}
            private val bound = CountDownLatch(1)
            private val path = directory.resolve("parent.sock")
            val context = SupervisorContext(
                path.toString(),
                directory,
                MockProcess(pid.value.toLong()),
                Thread(),
                SocketHelperProcess.ConfinedStartup(bootstrap, bootstrap),
                bootstrap.copy(identity = child),
            )
            private val sockets = object : SocketManager by RealSocketManager.capture(native) {
                override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> =
                    RealSocketManager.capture(native).createUnixServer(socketPath).also { bound.countDown() }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                    RealSocketManager.capture(native).close(fd)
                    afterSocketClose(fd)
                }
            }
            private val daemon = SeccompDaemonEngine(
                path.toString(),
                notifHandlerFactory = { _, _ -> error("No notification listener") },
                engine = native,
                socketManager = sockets,
                hooks = io.mazewall.platform.seccomp.daemon.SeccompConnectionHooks(
                    beforeListener = { socket, deadline ->
                        firstAccepted.complete(socket)
                        if (retirementReply == null) {
                            control.handle(socket, deadline)
                        } else {
                            val channel = ConfinedHelperChannel(native, socket, { false }, { true })
                            val cap = ConfinedDeadline.mint(native) { 5_000_000_000 }
                            assertContentEquals(byteArrayOf(0xe4.toByte()), channel.receive(1, cap))
                            val request = kotlin.test.assertIs<Frame.Control>(channel.receiveFrame(cap).frame)
                            assertEquals(ControlTag.Retire, request.tag)
                            channel.sendFrame(retirementReply(request), cap)
                            PreludeOutcome.Handled
                        }
                    },
                    afterConnectionClosed = { socket, outcome ->
                        control.connectionClosed(socket, outcome)
                        if (socket === firstAccepted.getNow(null)) firstCleanup.complete(outcome)
                    },
                ),
            )
            private val ended = CompletableFuture<Unit>()
            private val thread = Thread.ofPlatform().start {
                try {
                    daemon.run()
                    ended.complete(Unit)
                } catch (failure: Throwable) {
                    ended.completeExceptionally(failure)
                }
            }

            init {
                check(bound.await(2, TimeUnit.SECONDS))
                retryEndpoint = daemon::retryConnectionCleanup
            }

            override fun close() {
                daemon.triggerGlobalShutdown("parent fixture")
                thread.join(10_000)
                check(!thread.isAlive)
                ended.get(1, TimeUnit.SECONDS)
            }
        }

        fun setup(
            connection: Connection,
            deadline: ConfinedDeadline,
        ) {
            connection.start(Frame.Setup(manifest, deadline))
            val setup = try {
                helper.receiveFrame(deadline).frame
            } catch (failure: Exception) {
                try {
                    connection.loopEnded.get(1, TimeUnit.SECONDS)
                } catch (controlFailure: Exception) {
                    failure.addSuppressed(controlFailure)
                }
                throw failure
            }
            assertEquals(Frame.Setup(manifest, deadline).binding, (setup as Frame.Setup).binding)
            helper.sendFrame(Frame.Control(ControlTag.SetupAccepted, manifest.binding, deadline))
            assertEquals(Frame.Control(ControlTag.SetupAccepted, manifest.binding, deadline), connection.parent.receiveFrame(deadline).frame)
            roots.forEachIndexed { index, root ->
                val offer = Frame.Root(RootTag.Offer, manifest.binding, index, deadline)
                val expected = Frame.Root(RootTag.Expected, manifest.binding, index, deadline)
                val received = Frame.Root(RootTag.Received, manifest.binding, index, deadline)
                connection.parent.sendFrame(offer)
                assertEquals(expected, connection.parent.receiveFrame(deadline).frame)
                duplicateHelperResource(root.descriptor, native).use { connection.parent.sendDescriptor(it, deadline) }
                assertEquals(offer, helper.receiveFrame(deadline).frame)
                helper.sendFrame(expected)
                helper.receiveDescriptor(deadline).use { assertEquals(manifest.capabilities[index].identity, observations.observeRoot(it.handle)) }
                helper.sendFrame(received)
                assertEquals(received, connection.parent.receiveFrame(deadline).frame, "Root $index must complete before the next offer")
            }
            val complete = Frame.Root(RootTag.Complete, manifest.binding, 2, deadline)
            connection.parent.sendFrame(complete)
            assertEquals(complete, helper.receiveFrame(deadline).frame)
            val ready = Frame.Control(ControlTag.Ready, manifest.binding, deadline)
            helper.sendFrame(ready)
            assertEquals(ready, connection.parent.receiveFrame(deadline).frame)
        }

        override fun close() {
            try {
                connections.forEach { it.closeAllowed.countDown() }
                connections.forEach(Connection::close)
            } finally {
                try {
                    pool.close()
                } finally {
                    if (helperSockets.second.isLiveForIo()) helperSockets.second.close(native.fileSystem)
                    roots.asReversed().forEach(PreparedPath::close)
                    plan.close()
                    resources.close()
                }
            }
        }
    }

    @Test
    fun `parent propagates fatal transfer rollback after ordinary setup rejection`() {
        val fatal = LinkageError("controlled pre-dispatch transfer close failure")
        val armed = AtomicBoolean()
        val attempts = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        var parentThread: Thread? = null
        RelayFixture(directory, decorateNative = { real ->
            object : NativeEngine by real {
                override val fileSystem = object : NativeFileSystem by real.fileSystem {
                    override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        if (armed.get() && Thread.currentThread() === parentThread) {
                            attempts.add(fd)
                            if (attempts.size == 1) throw fatal
                        }
                        return real.fileSystem.close(fd)
                    }
                }
            }
        }).use { f ->
            f.Endpoint().use { endpoint ->
                val retained = CompletableFuture<ConfinedHelperSession>()
                val result = CompletableFuture<ConfinedHelperPreparation>()
                val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                val thread = Thread.ofPlatform().unstarted {
                    try {
                        result.complete(SupervisorInstaller.prepareConfinedHelper(f.plan, endpoint.context, f.native, deadline, { retained.complete(it) }))
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
                parentThread = thread
                thread.start()
                try {
                    val session = retained.get(2, TimeUnit.SECONDS)
                    kotlin.test.assertIs<Frame.Setup>(f.helper.receiveFrame(deadline).frame)
                    f.helper.sendFrame(Frame.Control(ControlTag.SetupAccepted, f.manifest.binding, deadline))
                    kotlin.test.assertIs<Frame.Root>(f.helper.receiveFrame(deadline).frame)
                    armed.set(true)
                    f.helper.sendFrame(Frame.Control(ControlTag.Ready, f.manifest.binding, deadline))
                    val thrown = assertFailsWith<java.util.concurrent.ExecutionException> { result.get(2, TimeUnit.SECONDS) }
                    kotlin.test.assertSame(fatal, thrown.cause)
                    assertTrue(fatal.suppressed.any { it is Exception }, "Ordinary setup failure must remain as evidence")
                    assertEquals(2, attempts.size, "Transfer and control socket cleanup must both be attempted")
                    assertTrue(attempts[0].isLiveForIo(), "Failed transfer close retains its original owner")
                    assertTrue(!attempts[1].isLiveForIo(), "Independent control socket close must succeed")
                    assertTrue(!session.retired)
                    assertFails { session.requireReady(f.manifest.binding, ConfinedDeadline.mint(f.native) { 1_000_000_000 }) }
                    f.plan.validate()
                } finally {
                    armed.set(false)
                    thread.interrupt()
                    thread.join(6000)
                    check(!thread.isAlive)
                    val session = retained.get(2, TimeUnit.SECONDS)
                    session.retire(ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                }
            }
        }
    }

    @Test
    fun `parent preparation retains session before setup and publishes Ready only after every root and helper Ready`() {
        RelayFixture(directory).use { f ->
            f.Endpoint().use { endpoint ->
                val retained = CompletableFuture<ConfinedHelperSession>()
                val result = CompletableFuture<ConfinedHelperPreparation>()
                val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                val thread = Thread.ofPlatform().start {
                    try {
                        result.complete(SupervisorInstaller.prepareConfinedHelper(f.plan, endpoint.context, f.native, deadline, { retained.complete(it) }))
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
                try {
                    val session = retained.get(2, TimeUnit.SECONDS)
                    val binding = f.manifest.binding
                    val setup = kotlin.test.assertIs<Frame.Setup>(f.helper.receiveFrame(deadline).frame)
                    assertEquals(binding, setup.binding)
                    assertEquals(deadline, setup.deadline)
                    assertEquals(f.manifest.capabilities, setup.manifest.capabilities)
                    assertEquals(f.manifest.ancestry.canonical, setup.manifest.ancestry.canonical)
                    assertTrue(!result.isDone)
                    f.helper.sendFrame(Frame.Control(ControlTag.SetupAccepted, binding, deadline))
                    f.manifest.capabilities.forEachIndexed { index, capability ->
                        assertEquals(Frame.Root(RootTag.Offer, binding, index, deadline), f.helper.receiveFrame(deadline).frame)
                        f.helper.sendFrame(Frame.Root(RootTag.Expected, binding, index, deadline))
                        f.helper.receiveDescriptor(deadline).use { assertEquals(capability.identity, f.observations.observeRoot(it.handle)) }
                        assertTrue(!result.isDone)
                        f.helper.sendFrame(Frame.Root(RootTag.Received, binding, index, deadline))
                    }
                    assertEquals(Frame.Root(RootTag.Complete, binding, 2, deadline), f.helper.receiveFrame(deadline).frame)
                    assertTrue(!result.isDone, "COMPLETE is not helper Ready")
                    f.helper.sendFrame(Frame.Control(ControlTag.Ready, binding, deadline))
                    assertEquals(ConfinedHelperPreparation.Ready(binding, session), result.get(2, TimeUnit.SECONDS))
                    session.requireReady(binding, ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                    f.plan.validate()
                    assertTrue(!session.retired)
                    session.retire(ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                    assertTrue(session.retired)
                    assertEquals(1, f.reaps.get())
                    assertFails { session.requireReady(binding, ConfinedDeadline.mint(f.native) { 5_000_000_000 }) }
                    session.retire(ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                    assertEquals(1, f.reaps.get())
                    f.plan.validate()
                } finally {
                    thread.interrupt()
                    thread.join(6000)
                    check(!thread.isAlive)
                }
            }
        }
    }

    @Test
    fun `parent rejects premature helper Ready and retains its plan through authenticated cleanup reconnect`() {
        RelayFixture(directory).use { f ->
            f.Endpoint().use { endpoint ->
                val retained = CompletableFuture<ConfinedHelperSession>()
                val result = CompletableFuture<ConfinedHelperPreparation>()
                val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                val thread = Thread.ofPlatform().start {
                    try {
                        result.complete(SupervisorInstaller.prepareConfinedHelper(f.plan, endpoint.context, f.native, deadline, { retained.complete(it) }))
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
                try {
                    val session = retained.get(2, TimeUnit.SECONDS)
                    kotlin.test.assertIs<Frame.Setup>(f.helper.receiveFrame(deadline).frame)
                    f.helper.sendFrame(Frame.Control(ControlTag.Ready, f.manifest.binding, deadline))
                    kotlin.test.assertIs<ConfinedHelperPreparation.Rejected>(result.get(2, TimeUnit.SECONDS))
                    assertTrue(!session.retired)
                    f.plan.validate()
                    session.retire(ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                    assertTrue(session.retired)
                    assertEquals(1, f.reaps.get())
                    f.plan.validate()
                } finally {
                    thread.interrupt()
                    thread.join(6000)
                    check(!thread.isAlive)
                }
            }
        }
    }

    @Test
    fun `parent retirement refuses stale binding deadline and acquired helper incarnation`() {
        val corruptions = listOf<(Frame.Retired) -> Frame>(
            { it.copy(binding = it.binding.copy(manifestDigest = "0".repeat(64))) },
            { it.copy(deadline = ConfinedDeadline(it.deadline.nanos - 1)) },
            { it.copy(helperPid = Pid(it.helperPid.value + 1)) },
            { it.copy(helperStartTicks = it.helperStartTicks + 1) },
        )
        corruptions.forEachIndexed { index, corrupt ->
            RelayFixture(Files.createDirectory(directory.resolve("receipt-$index"))).use { f ->
                var reject = true
                val endpoint = f.Endpoint { request ->
                    val receipt = Frame.Retired(request.binding, StopReason.Cancel, f.child.pid, f.child.startTimeTicks, request.deadline)
                    if (reject) corrupt(receipt) else receipt
                }
                endpoint.use {
                    val retained = CompletableFuture<ConfinedHelperSession>()
                    val result = SupervisorInstaller.prepareConfinedHelper(f.plan, endpoint.context, f.native, ConfinedDeadline.mint(f.native) { 5_000_000_000 }, { retained.complete(it) }, { true })
                    kotlin.test.assertIs<ConfinedHelperPreparation.Rejected>(result)
                    val session = retained.get(1, TimeUnit.SECONDS)
                    assertFails { session.retire(ConfinedDeadline.mint(f.native) { 5_000_000_000 }) }
                    assertTrue(!session.retired, "Untrusted receipt cannot release original resources")
                    f.plan.validate()
                    reject = false
                    session.retire(ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                    assertTrue(session.retired)
                    f.plan.validate()
                }
            }
        }
    }

    @Test
    fun `parent exhausted preparation returns rejection with the registered session still reachable for retirement`() {
        RelayFixture(directory).use { f ->
            f.Endpoint().use { endpoint ->
                val retained = CompletableFuture<ConfinedHelperSession>()
                val result = SupervisorInstaller.prepareConfinedHelper(f.plan, endpoint.context, f.native, ConfinedDeadline(1), { retained.complete(it) })
                val rejection = kotlin.test.assertIs<ConfinedHelperPreparation.Rejected>(result)
                assertEquals(ConfinedRejectReason.Timeout, rejection.reason)
                f.plan.validate()
                val session = retained.get(1, TimeUnit.SECONDS)
                assertTrue(!session.retired)
                session.retire(ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                assertTrue(session.retired)
                assertEquals(1, f.reaps.get())
            }
        }
    }

    @Test
    fun `parent authentication preserves native permission errno before any setup or root transfer`() {
        RelayFixture(directory).use { f ->
            f.Endpoint().use { endpoint ->
                for (errno in listOf(1, 13)) {
                    val native = object : NativeEngine by f.native {
                        override val raw = object : RawSyscallOperations by f.native.raw {
                            override fun syscall(
                                nr: Long,
                                a1: NativeArg,
                                a2: NativeArg,
                                a3: NativeArg,
                                a4: NativeArg,
                                a5: NativeArg,
                                a6: NativeArg,
                            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                                if (nr == Arch.current().getsockopt.toLong()) LinuxNative.SyscallResult.Error(errno, -1L) else f.native.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                        }
                    }
                    val retained = CompletableFuture<ConfinedHelperSession>()
                    val result = SupervisorInstaller.prepareConfinedHelper(f.plan, endpoint.context, native, ConfinedDeadline.mint(native) { 5_000_000_000 }, { retained.complete(it) })
                    val rejection = kotlin.test.assertIs<ConfinedHelperPreparation.Rejected>(result)
                    assertEquals(ConfinedRejectReason.Permission, rejection.reason)
                    assertEquals(errno, kotlin.test.assertIs<ConfinedHelperRejection>(rejection.cause).errno)
                    assertTrue(!retained.get(1, TimeUnit.SECONDS).retired)
                    assertEquals(null, f.helper.pollFrame(ConfinedDeadline.mint(f.native) { 1_000_000_000 }))
                    f.plan.validate()
                }
            }
        }
    }

    @Test
    fun `control relays roots and read receipts through the shared helper stages before retirement`() {
        RelayFixture(directory).use { f ->
            val connection = f.connect()
            val setupDeadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
            f.setup(connection, setupDeadline)
            val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
            val binding = f.manifest.binding
            val request = ConfinedReadRequest(binding, 1, f.manifest.capabilities[0].purpose, 0, "support.jar")
            val read = Frame.Read(request, deadline)
            connection.parent.sendFrame(read)
            assertEquals(read, f.helper.receiveFrame(deadline).frame)
            val opened = Frame.Result(ResultTag.Opened, binding, 1, deadline)
            val expected = Frame.Result(ResultTag.Expected, binding, 1, deadline)
            val received = Frame.Result(ResultTag.Received, binding, 1, deadline)
            f.helper.sendFrame(opened)
            assertEquals(expected, f.helper.receiveFrame(deadline).frame)
            NativeArena.ofConfined().use { arena ->
                val descriptor = f.native.fileSystem
                    .open(arena.allocateFrom(f.roots[1].requested.toString()), OpenFlags(0x80000))
                    .getFdOrThrow("control result", FileDescriptorRole.Generic)
                SupervisorOwnedFd(descriptor, f.native.fileSystem).use { result ->
                    f.helper.sendDescriptor(result, deadline)
                    assertEquals(opened, connection.parent.receiveFrame(deadline).frame)
                    connection.parent.sendFrame(expected)
                    connection.parent.receiveDescriptor(deadline).use { quarantine ->
                        assertEquals(f.observations.observeReadable(descriptor, f.native), f.observations.observeReadable(quarantine.handle, f.native))
                        assertEquals(null, f.helper.pollFrame(deadline), "No helper receipt before parent receipt")
                        assertEquals(null, connection.parent.pollFrame(deadline), "No parent DONE before helper close")
                        connection.parent.sendFrame(received)
                        assertEquals(received, f.helper.receiveFrame(deadline).frame)
                        result.close()
                        val done = Frame.Result(ResultTag.Done, binding, 1, deadline)
                        f.helper.sendFrame(done)
                        assertEquals(done, connection.parent.receiveFrame(deadline).frame)
                    }
                }
            }
            val cleanupDeadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
            connection.parent.sendFrame(Frame.Control(ControlTag.Retire, binding, cleanupDeadline))
            assertEquals(Frame.Retired(binding, StopReason.Normal, f.child.pid, f.child.startTimeTicks, cleanupDeadline), connection.parent.receiveFrame(cleanupDeadline).frame)
            assertEquals(PreludeOutcome.Handled, connection.loopEnded.get(2, TimeUnit.SECONDS))
            assertEquals(1, f.reaps.get())
        }
    }

    @Test
    fun `control accepts a valid receipt when forwarding completes during poll`() {
        val forwarding = CountDownLatch(1)
        val allowForwarding = CountDownLatch(1)
        val forwarded = CountDownLatch(1)
        val polling = CountDownLatch(1)
        val allowPoll = CountDownLatch(1)
        val armed = AtomicBoolean()
        try {
            RelayFixture(directory, decorateNative = { real ->
                object : NativeEngine by real {
                    override val raw = object : RawSyscallOperations by real.raw {
                        override fun poll(
                            fds: ManagedSegment,
                            nfds: Long,
                            timeout: Int,
                        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                            if (Thread.currentThread().name == "confined-control-fixture" && armed.compareAndSet(true, false)) {
                                polling.countDown()
                                check(allowPoll.await(2, TimeUnit.SECONDS))
                            }
                            return real.raw.poll(fds, nfds, timeout)
                        }
                    }
                }
            }, beforeForwarded = {
                forwarding.countDown()
                check(allowForwarding.await(2, TimeUnit.SECONDS))
            }, afterForwarded = { forwarded.countDown() }).use { f ->
                try {
                    val connection = f.connect()
                    f.setup(connection, ConfinedDeadline.mint(f.native) { 5_000_000_000 })
                    val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                    val binding = f.manifest.binding
                    val read = Frame.Read(ConfinedReadRequest(binding, 1, f.manifest.capabilities[0].purpose, 0, "support.jar"), deadline)
                    connection.parent.sendFrame(read)
                    assertEquals(read, f.helper.receiveFrame(deadline).frame)
                    val opened = Frame.Result(ResultTag.Opened, binding, 1, deadline)
                    val expected = Frame.Result(ResultTag.Expected, binding, 1, deadline)
                    val received = Frame.Result(ResultTag.Received, binding, 1, deadline)
                    f.helper.sendFrame(opened)
                    assertEquals(expected, f.helper.receiveFrame(deadline).frame)
                    NativeArena.ofConfined().use { arena ->
                        val descriptor = f.native.fileSystem
                            .open(arena.allocateFrom(f.roots[1].requested.toString()), OpenFlags(0x80000))
                            .getFdOrThrow("ordering result", FileDescriptorRole.Generic)
                        SupervisorOwnedFd(descriptor, f.native.fileSystem).use { result ->
                            f.helper.sendDescriptor(result, deadline)
                            assertEquals(opened, connection.parent.receiveFrame(deadline).frame)
                            connection.parent.sendFrame(expected)
                            connection.parent.receiveDescriptor(deadline).use { actual ->
                                assertEquals(f.observations.observeReadable(descriptor, f.native), f.observations.observeReadable(actual.handle, f.native))
                                assertTrue(forwarding.await(2, TimeUnit.SECONDS))
                                armed.set(true)
                                assertTrue(polling.await(2, TimeUnit.SECONDS))
                                connection.parent.sendFrame(received)
                                allowForwarding.countDown()
                                assertTrue(forwarded.await(2, TimeUnit.SECONDS))
                                allowPoll.countDown()
                                val helperReceipt = try {
                                    f.helper.receiveFrame(deadline).frame
                                } catch (failure: Exception) {
                                    try {
                                        connection.loopEnded.get(1, TimeUnit.SECONDS)
                                    } catch (controlFailure: java.util.concurrent.ExecutionException) {
                                        throw requireNotNull(controlFailure.cause)
                                    }
                                    throw failure
                                }
                                assertEquals(received, helperReceipt)
                                result.close()
                                val done = Frame.Result(ResultTag.Done, binding, 1, deadline)
                                f.helper.sendFrame(done)
                                assertEquals(done, connection.parent.receiveFrame(deadline).frame)
                            }
                        }
                    }
                } finally {
                    allowForwarding.countDown()
                    allowPoll.countDown()
                }
            }
        } finally {
            allowForwarding.countDown()
            allowPoll.countDown()
        }
    }

    @Test
    fun `idle control observes helper IO failure without waiting for another parent frame`() {
        RelayFixture(directory).use { f ->
            val old = f.connect(holdClose = true)
            f.setup(old, ConfinedDeadline.mint(f.native) { 5_000_000_000 })
            f.loseHelper()
            val failed = assertFailsWith<java.util.concurrent.ExecutionException> { old.loopEnded.get(2, TimeUnit.SECONDS) }
            kotlin.test.assertIs<IOException>(failed.cause)
            old.closeAllowed.countDown()
            old.closed.get(2, TimeUnit.SECONDS)
            val reconnect = f.connect()
            val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
            reconnect.start(Frame.Control(ControlTag.Retire, f.manifest.binding, deadline))
            assertEquals(Frame.Retired(f.manifest.binding, StopReason.Failure, f.child.pid, f.child.startTimeTicks, deadline), reconnect.parent.receiveFrame(deadline).frame)
            assertEquals(1, f.reaps.get())
        }
    }

    @Test
    fun `cleanup reconnect waits for old accepted socket release and retains every finite stop reason`() {
        for (reason in StopReason.entries) {
            val path = Files.createDirectory(directory.resolve(reason.name))
            RelayFixture(path).use { f ->
                val old = f.connect(holdClose = true)
                val setupDeadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                f.setup(old, setupDeadline)
                val stopDeadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                old.parent.sendFrame(Frame.Stop(ConfinedHelperProtocol.StopTag.Stop, f.manifest.binding, reason, stopDeadline))
                assertEquals(PreludeOutcome.Handled, old.loopEnded.get(2, TimeUnit.SECONDS))
                val reconnect = f.connect(holdClose = true)
                val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                reconnect.start(Frame.Control(ControlTag.Retire, f.manifest.binding, deadline))
                assertEquals(null, reconnect.parent.pollFrame(deadline), "RETIRED overtook old accepted socket cleanup")
                val failure = IOException("controlled old socket pre-dispatch close failure")
                f.control.connectionClosed(old.accepted, SeccompCleanupOutcome.Incomplete(listOf(failure), listOf(old.accepted)))
                val rejected = assertFailsWith<java.util.concurrent.ExecutionException> { reconnect.loopEnded.get(2, TimeUnit.SECONDS) }
                kotlin.test.assertSame(failure, rejected.cause)
                assertEquals(null, reconnect.parent.pollFrame(deadline), "Incomplete cleanup was treated as release")
                val repeated = f.connect(holdClose = true)
                val retryDeadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                repeated.start(Frame.Control(ControlTag.Retire, f.manifest.binding, retryDeadline))
                assertEquals(Frame.Retired(f.manifest.binding, reason, f.child.pid, f.child.startTimeTicks, retryDeadline), repeated.parent.receiveFrame(retryDeadline).frame)
                old.closed.get(2, TimeUnit.SECONDS)
                assertEquals(1, f.retries.get())
                assertEquals(1, f.reaps.get())
            }
        }
    }

    @Test
    fun `cleanup reconnect cancels a claimed read but waits for its old conversation to close`() {
        RelayFixture(directory).use { f ->
            val old = f.connect(holdClose = true)
            f.setup(old, ConfinedDeadline.mint(f.native) { 5_000_000_000 })
            val deadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
            val binding = f.manifest.binding
            val read = Frame.Read(ConfinedReadRequest(binding, 1, f.manifest.capabilities[0].purpose, 0, "support.jar"), deadline)
            old.parent.sendFrame(read)
            assertEquals(read, f.helper.receiveFrame(deadline).frame)
            val opened = Frame.Result(ResultTag.Opened, binding, 1, deadline)
            f.helper.sendFrame(opened)
            assertEquals(Frame.Result(ResultTag.Expected, binding, 1, deadline), f.helper.receiveFrame(deadline).frame)
            NativeArena.ofConfined().use { arena ->
                val descriptor = f.native.fileSystem
                    .open(arena.allocateFrom(f.roots[1].requested.toString()), OpenFlags(0x80000))
                    .getFdOrThrow("claimed control result", FileDescriptorRole.Generic)
                SupervisorOwnedFd(descriptor, f.native.fileSystem).use { result ->
                    f.helper.sendDescriptor(result, deadline)
                    assertEquals(opened, old.parent.receiveFrame(deadline).frame)
                    val reconnect = f.connect(holdClose = true)
                    val cleanupDeadline = ConfinedDeadline.mint(f.native) { 5_000_000_000 }
                    reconnect.start(Frame.Control(ControlTag.Retire, binding, cleanupDeadline))
                    assertEquals(null, reconnect.parent.pollFrame(cleanupDeadline), "Claimed old conversation still owns native I/O")
                    assertFailsWith<java.util.concurrent.CancellationException> { old.loopEnded.get(2, TimeUnit.SECONDS) }
                    assertEquals(null, reconnect.parent.pollFrame(cleanupDeadline), "Loop termination is not accepted-socket release")
                    old.closeAllowed.countDown()
                    old.closed.get(2, TimeUnit.SECONDS)
                    assertEquals(Frame.Retired(binding, StopReason.Cancel, f.child.pid, f.child.startTimeTicks, cleanupDeadline), reconnect.parent.receiveFrame(cleanupDeadline).frame)
                    assertEquals(1, f.reaps.get())
                    assertTrue(descriptor.isLiveForIo(), "Synthetic helper's distinct result owner belongs to the test")
                }
            }
        }
    }

    private class ControlFixture : AutoCloseable {
        val wire = ConfinedHelperChannelTest.Fixture(81210)
        private val real = LinuxNative.snapshot()
        private val pid = Pid(ProcessHandle.current().pid().toInt())
        val bootstrap = HelperSecurityObservation().observeAncestry(pid, real.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
        val childIdentity = ProcessIdentity(Pid(7001), 91)
        val binding = ConfinedHelperTestData.manifest().binding.let { it.copy(owner = it.owner.copy(ownerPid = pid, ownerStartTicks = bootstrap.identity.startTimeTicks)) }
        var peerPid = pid.value
        var creates = 0
        var reaps = 0
        var failReap = false
        val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
            creates++
            object : SocketHelper {
                override val confinedIdentity = childIdentity

                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = error("Legacy work")

                override fun close() {
                    reaps++
                    if (failReap) throw IOException("controlled reap failure")
                }
            }
        }

        init {
            wire.native.process.tid = real.process.gettid()
            val clock = requireNotNull(wire.native.onSyscall)
            val fields = bootstrap.ancestry.canonical
                .lineSequence()
                .filter { it.isNotEmpty() }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val uid = fields
                .getValue("uid")
                .split(',')[1]
                .toLong()
                .toInt()
            val gid = fields
                .getValue("gid")
                .split(',')[1]
                .toLong()
                .toInt()
            wire.native.onSyscall = { nr, a, b, c, d, e, f ->
                when (nr) {
                    Arch.current().recvfrom.toLong() -> {
                        assertEquals(0x42L, d.asLong)
                        (b as NativeArg.MemoryArg).value.writeByte(0, wire.incoming.first())
                        LinuxNative.SyscallResult.Success(1L)
                    }
                    Arch.current().getsockopt.toLong() -> {
                        val data = (d as NativeArg.MemoryArg).value
                        data.writeInt(0, peerPid)
                        data.writeInt(4, uid)
                        data.writeInt(8, gid)
                        (e as NativeArg.MemoryArg).value.writeInt(0, 12)
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    else -> clock(nr, a, b, c, d, e, f)
                }
            }
            pool.prewarm()
        }

        fun control(observations: HelperSecurityObservation = HelperSecurityObservation()) =
            ConfinedHelperControl(pool, SocketHelperProcess.ConfinedStartup(bootstrap, bootstrap), wire.native, observations)

        fun retire(
            binding: ConfinedBinding = this.binding,
            deadline: ConfinedDeadline = ConfinedDeadline(4_000_000_000),
        ) {
            wire.input(byteArrayOf(0xe4.toByte()) + ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.Retire, binding, deadline)))
        }

        override fun close() {
            failReap = false
            try {
                pool.close()
            } finally {
                wire.close()
            }
        }
    }

    @Test
    fun `authenticated prebinding retirement retries the same spent child and repeats exact receipt`() {
        ControlFixture().use { f ->
            val control = f.control()
            f.failReap = true
            f.retire()
            assertFailsWith<IOException> { control.handle(f.wire.socket, Deadline.afterMillis(5000)) }
            assertEquals(0, f.wire.outgoing.size())
            assertEquals(1, f.creates)
            assertEquals(1, f.reaps)
            f.failReap = false
            val deadline = ConfinedDeadline(4_500_000_000)
            f.retire(deadline = deadline)
            assertEquals(PreludeOutcome.Handled, control.handle(f.wire.socket, Deadline.afterMillis(5000)))
            val expected = ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Retired(f.binding, StopReason.Normal, f.childIdentity.pid, f.childIdentity.startTimeTicks, deadline))
            assertContentEquals(expected, f.wire.outgoing.toByteArray())
            f.retire(deadline = deadline)
            assertEquals(PreludeOutcome.Handled, control.handle(f.wire.socket, Deadline.afterMillis(5000)))
            assertContentEquals(expected + expected, f.wire.outgoing.toByteArray())
            assertEquals(1, f.creates)
            assertEquals(2, f.reaps)
            assertTrue(f.wire.socket.isLiveForIo(), "Accepted socket remains with the engine")
            assertFails { f.pool.prewarm() }
        }
    }

    @Test
    fun `retirement cannot overwrite the retained binding after physical reap`() {
        ControlFixture().use { f ->
            val control = f.control()
            f.retire()
            assertEquals(PreludeOutcome.Handled, control.handle(f.wire.socket, Deadline.afterMillis(5000)))
            val receipt = f.wire.outgoing.toByteArray()
            f.retire(f.binding.copy(owner = f.binding.owner.copy(domainGeneration = f.binding.owner.domainGeneration + 1)))
            assertFails { control.handle(f.wire.socket, Deadline.afterMillis(5000)) }
            assertContentEquals(receipt, f.wire.outgoing.toByteArray())
            f.retire()
            assertEquals(PreludeOutcome.Handled, control.handle(f.wire.socket, Deadline.afterMillis(5000)))
            assertContentEquals(receipt + receipt, f.wire.outgoing.toByteArray())
            assertEquals(1, f.reaps)
        }
    }

    @Test
    fun `control authenticates actual parent and time domain before parsing foreign deadlines`() {
        for (deniedObservation in listOf(false, true)) {
            ControlFixture().use { f ->
                val denied = java.nio.file.AccessDeniedException("controlled namespace")
                val control = if (deniedObservation) f.control(HelperSecurityObservation(namespaceKey = { throw denied })) else f.control()
                if (!deniedObservation) f.peerPid++
                f.retire()
                val failure = assertFails { control.handle(f.wire.socket, Deadline.afterMillis(5000)) }
                if (deniedObservation) {
                    val rejection = kotlin.test.assertIs<ConfinedHelperRejection>(failure)
                    assertEquals(ConfinedRejectReason.Permission, rejection.reason)
                    assertEquals(13, rejection.errno)
                }
                assertTrue(f.wire.requested.isEmpty())
                assertEquals(0, f.wire.outgoing.size())
                assertEquals(0, f.reaps)
            }
        }
    }

    @Test
    fun `ready setup admits an authenticated live listener prelude`() {
        RelayFixture(directory).use { fixture ->
            val setup = fixture.connect()
            val deadline = ConfinedDeadline.mint(fixture.native) { 5_000_000_000 }
            fixture.setup(setup, deadline)
            fixture.connect().use { live ->
                live.parent.send(livePrelude(fixture.manifest.binding, 3), deadline)
                val outcome = live.loopEnded.get(2, TimeUnit.SECONDS)
                assertEquals("LiveListener", outcome.javaClass.simpleName)
            }
            fixture.connect().use { replay ->
                replay.parent.send(livePrelude(fixture.manifest.binding, 3), deadline)
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> { replay.loopEnded.get(2, TimeUnit.SECONDS) }
                assertEquals("Live listener attempt is spent", failure.cause?.message)
            }
            fixture.connect().use { wrong ->
                val foreign = fixture.manifest.binding.copy(manifestDigest = "0".repeat(64))
                wrong.parent.send(livePrelude(foreign, 4), deadline)
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> { wrong.loopEnded.get(2, TimeUnit.SECONDS) }
                assertEquals("Live setup is not active", failure.cause?.message)
            }
        }
    }

    @Test
    fun `live admission revocation and overlap refuse an in flight finish`() {
        RelayFixture(directory).use { fixture ->
            val setup = fixture.connect()
            val deadline = ConfinedDeadline.mint(fixture.native) { 5_000_000_000 }
            fixture.setup(setup, deadline)
            for (overlap in listOf(false, true)) {
                fixture.connect(holdClose = true).use { live ->
                    live.parent.send(livePrelude(fixture.manifest.binding, if (overlap) 12 else 11), deadline)
                    val accepted = assertIs<PreludeOutcome.LiveListener>(live.loopEnded.get(2, TimeUnit.SECONDS))
                    val admission = assertIs<ConfinedHelperControl.LiveAdmission>(accepted.admission)
                    admission.attach(live.accepted)
                    val serial = checkNotNull(admission.begin(live.accepted))
                    if (overlap) {
                        assertEquals(null, admission.begin(live.accepted))
                    } else {
                        admission.close()
                    }
                    val finished: Any = admission.finish(serial, true)
                    assertEquals(false, finished)
                }
            }
        }
    }

    @Test
    fun `live listener is refused while setup is not ready`() {
        RelayFixture(directory).use { fixture ->
            val setup = fixture.connect()
            val deadline = ConfinedDeadline.mint(fixture.native) { 5_000_000_000 }
            setup.start(Frame.Setup(fixture.manifest, deadline))
            assertIs<Frame.Setup>(fixture.helper.receiveFrame(deadline).frame)
            fixture.connect().use { live ->
                live.parent.send(livePrelude(fixture.manifest.binding, 1), deadline)
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> { live.loopEnded.get(2, TimeUnit.SECONDS) }
                assertEquals("Live setup is not active", failure.cause?.message)
            }
        }
    }

    @Test
    fun `live listener attempts remain spent until setup ends`() {
        RelayFixture(directory).use { fixture ->
            val setup = fixture.connect()
            fixture.setup(setup, ConfinedDeadline.mint(fixture.native) { 5_000_000_000 })
            for (serial in 1L..201L) {
                val deadline = ConfinedDeadline.mint(fixture.native) { 5_000_000_000 }
                fixture.connect().use { live ->
                    live.parent.send(livePrelude(fixture.manifest.binding, serial), deadline)
                    if (serial <= 200) {
                        val outcome = live.loopEnded.get(2, TimeUnit.SECONDS)
                        assertEquals("LiveListener", outcome.javaClass.simpleName)
                    } else {
                        val failure = assertFailsWith<java.util.concurrent.ExecutionException> { live.loopEnded.get(2, TimeUnit.SECONDS) }
                        assertEquals("Live listener attempts exhausted", failure.cause?.message)
                    }
                }
            }
        }
    }

    @Test
    fun `retirement revokes a reserved live listener before helper reap`() {
        RelayFixture(directory).use { fixture ->
            val setup = fixture.connect()
            val deadline = ConfinedDeadline.mint(fixture.native) { 5_000_000_000 }
            fixture.setup(setup, deadline)
            val live = fixture.connect(holdClose = true)
            live.parent.send(livePrelude(fixture.manifest.binding, 1), deadline)
            val result = assertIs<PreludeOutcome.LiveListener>(live.loopEnded.get(2, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.beforeReap = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                setup.parent.sendFrame(Frame.Control(ControlTag.Retire, fixture.manifest.binding, deadline))
                check(entered.await(5, TimeUnit.SECONDS))
                val admission = assertIs<ConfinedHelperControl.LiveAdmission>(result.admission)
                assertFailsWith<IllegalStateException> { admission.attach(live.accepted) }
            } finally {
                release.countDown()
                live.closeAllowed.countDown()
            }
            assertIs<Frame.Retired>(setup.parent.receiveFrame(deadline).frame)
        }
    }

    private fun livePrelude(
        binding: ConfinedBinding,
        serial: Long,
    ): ByteArray {
        val owner = binding.owner
        return java.nio.ByteBuffer
            .allocate(1 + 4 + 4 + 8 + 8 + 32 * 3 + 4 + 8 * 3)
            .put(0xe5.toByte())
            .putInt(1)
            .putInt(owner.ownerPid.value)
            .putLong(owner.ownerStartTicks)
            .putLong(owner.domainGeneration)
            .put(HexFormat.of().parseHex(owner.resourceGeneration))
            .put(HexFormat.of().parseHex(owner.policyDigest))
            .put(HexFormat.of().parseHex(binding.manifestDigest))
            .putInt(owner.ownerPid.value)
            .putLong(1)
            .putLong(2)
            .putLong(serial)
            .array()
    }

    @Test
    fun `control leaves legacy descriptor traffic queued and rejects unknown markers`() {
        for (marker in listOf(0, 1, 255)) {
            ControlFixture().use { f ->
                f.wire.input(marker)
                val control = f.control()
                if (marker == 0) {
                    assertEquals(PreludeOutcome.LegacyListener, control.handle(f.wire.socket, Deadline.afterMillis(5000)))
                } else {
                    assertFails { control.handle(f.wire.socket, Deadline.afterMillis(5000)) }
                }
                assertEquals(listOf(marker.toByte()), f.wire.incoming.toList())
                assertTrue(f.wire.requested.isEmpty())
                assertEquals(0, f.reaps)
            }
        }
    }

    @Test
    fun `complete final frame remains readable after peer shutdown`() {
        val native = LinuxNative.snapshot()
        NativeArena.ofConfined().use { arena ->
            val pair = native.networking.socketpair(1, 1 or 0x80000, 0, arena.allocate(8))
            val receiver = pair.getFdOrThrow("final frame receiver", FileDescriptorRole.UnixSocket)
            SupervisorOwnedFd(receiver, native.fileSystem).use {
                val sender = pair.getFdOrThrow("final frame sender", FileDescriptorRole.UnixSocket)
                val deadline = ConfinedDeadline.mint(native) { 1_000_000_000 }
                val frame = Frame.Retired(ConfinedHelperTestData.manifest().binding, StopReason.Normal, Pid(1234), 19, deadline)
                SupervisorOwnedFd(sender, native.fileSystem).use {
                    ConfinedHelperChannel(native, sender, { false }, { true }).sendFrame(frame)
                }
                val channel = ConfinedHelperChannel(native, receiver, { false }, { true })
                assertEquals(frame, channel.receiveFrame(deadline).frame)
                assertFailsWith<IOException> { channel.pollFrame(deadline) }
            }
        }
    }

    @Test
    fun `real socket peek preserves the queued SCM receipt and original owner`() {
        val native = LinuxNative.snapshot()
        NativeArena.ofConfined().use { arena ->
            val pair = native.networking.socketpair(1, 1 or 0x80000, 0, arena.allocate(8))
            val receiver = pair.getFdOrThrow("prelude socketpair receiver", FileDescriptorRole.UnixSocket)
            SupervisorOwnedFd(receiver, native.fileSystem).use {
                val sender = pair.getFdOrThrow("prelude socketpair sender", FileDescriptorRole.UnixSocket)
                SupervisorOwnedFd(sender, native.fileSystem).use {
                    val source = native.networking.socket(1, 1 or 0x80000, 0).getFdOrThrow("prelude transferred socket", FileDescriptorRole.UnixSocket)
                    SupervisorOwnedFd(source, native.fileSystem).use { owner ->
                        val read = ConfinedHelperChannel(native, receiver, { false }, { true })
                        val write = ConfinedHelperChannel(native, sender, { false }, { true })
                        val deadline = ConfinedDeadline.mint(native) { 1_000_000_000 }
                        write.sendDescriptor(owner, deadline)
                        repeat(3) { assertEquals(0, read.peekByte(deadline)) }
                        assertTrue(source.isLiveForIo())
                        val key = Files.readAttributes(Path.of("/proc/self/fd/${source.value}"), "unix:fileKey").getValue("fileKey")
                        read.receiveDescriptor(deadline).use { received ->
                            assertTrue(received.handle.value != source.value)
                            assertEquals(key, Files.readAttributes(Path.of("/proc/self/fd/${received.handle.value}"), "unix:fileKey").getValue("fileKey"))
                        }
                        assertTrue(source.isLiveForIo())
                    }
                }
            }
        }
    }

    @Test
    fun `prelude sniff leaves legacy descriptor byte queued without ancillary adoption`() {
        for (byte in listOf(0, 0xe4)) {
            ConfinedHelperChannelTest.Fixture().use { wire ->
                wire.input(byte)
                val clock = requireNotNull(wire.native.onSyscall)
                var peeks = 0
                wire.native.onSyscall = { nr, fd, buffer, count, flags, address, length ->
                    if (nr == Arch.current().recvfrom.toLong()) {
                        assertEquals(wire.socket.value.toLong(), fd.asLong)
                        assertEquals(1L, count.asLong)
                        assertEquals(0x42L, flags.asLong)
                        assertEquals(0L, address.asLong)
                        assertEquals(0L, length.asLong)
                        (buffer as NativeArg.MemoryArg).value.writeByte(0, wire.incoming.first())
                        peeks++
                        LinuxNative.SyscallResult.Success(1L)
                    } else {
                        clock(nr, fd, buffer, count, flags, address, length)
                    }
                }
                assertEquals(byte, wire.channel.peekByte(ConfinedDeadline(2_000_000_000)))
                assertEquals(1, peeks)
                assertEquals(listOf(byte.toByte()), wire.incoming.toList())
                assertTrue(wire.requested.isEmpty() && wire.closed.isEmpty())
            }
        }
    }

    @Test
    fun `stalled prelude sniff expires unchanged local cap before any descriptor receive`() {
        for (errno in listOf(4, 11)) {
            ConfinedHelperChannelTest.Fixture().use { wire ->
                val clock = requireNotNull(wire.native.onSyscall)
                wire.native.onSyscall = { nr, a, b, c, d, e, f ->
                    if (nr == Arch.current().recvfrom.toLong()) LinuxNative.SyscallResult.Error(errno, -1L) else clock(nr, a, b, c, d, e, f)
                }
                assertFailsWith<TimeoutException> { wire.channel.peekByte(ConfinedDeadline(1_075_000_000)) }
                assertTrue(wire.now <= 1_100_000_000)
                assertTrue(wire.requested.isEmpty() && wire.closed.isEmpty())
            }
        }
    }

    @Test
    fun `prelude EOF and permission errors remain terminal without listener receive`() {
        for (errno in listOf(0, 1, 13)) {
            ConfinedHelperChannelTest.Fixture().use { wire ->
                val clock = requireNotNull(wire.native.onSyscall)
                wire.native.onSyscall = { nr, a, b, c, d, e, f ->
                    if (nr != Arch.current().recvfrom.toLong()) {
                        clock(nr, a, b, c, d, e, f)
                    } else if (errno == 0) {
                        LinuxNative.SyscallResult.Success(0L)
                    } else {
                        LinuxNative.SyscallResult.Error(errno, -1L)
                    }
                }
                if (errno == 0) {
                    assertFailsWith<EOFException> { wire.channel.peekByte(ConfinedDeadline(2_000_000_000)) }
                } else {
                    val failure = assertFailsWith<ConfinedHelperRejection> { wire.channel.peekByte(ConfinedDeadline(2_000_000_000)) }
                    assertEquals(ConfinedRejectReason.Permission, failure.reason)
                    assertEquals(errno, failure.errno)
                }
                assertTrue(wire.requested.isEmpty() && wire.closed.isEmpty())
                assertFailsWith<IllegalStateException> { wire.channel.receive(1, ConfinedDeadline(2_000_000_000)) }
            }
        }
    }
}
