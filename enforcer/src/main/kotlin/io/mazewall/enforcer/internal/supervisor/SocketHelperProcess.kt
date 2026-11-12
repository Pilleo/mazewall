package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.supervisor.ConfinedHelperJob.Command
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ControlTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ResultTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.RootTag
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.socket.PrivateUnixEndpoint
import io.mazewall.platform.socket.RealSocketManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One reusable independent JVM; killing it interrupts native accept/connect without changing OFD flags. */
internal class SocketHelperProcess internal constructor(
    private val process: Process,
    private val channel: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val endpoint: PrivateUnixEndpoint,
    private val native: NativeEngine = LinuxNative.snapshot(),
    private val confined: ConfinedStartup? = null,
    private val observations: HelperSecurityObservation = HelperSecurityObservation(),
) : SocketHelper {
    internal data class ConfinedStartup(
        val parent: HelperSecurityObservation.Observation,
        val child: HelperSecurityObservation.Observation,
    ) {
        fun requireCommon() {
            parent.time.requireCommon(child.time)
            check(parent.ancestry.canonical == child.ancestry.canonical) { "Helper startup ancestry differs" }
        }
    }

    /** Exact acquired child identity; [close] remains responsible for physical retirement. */
    internal val processHandle: ProcessHandle
        get() = process.toHandle()

    override val confinedIdentity: ProcessIdentity? get() = confined?.child?.identity

    private val lifetime = SocketHelperLifetime(
        reap = { terminate(process) },
        closeChannel = { channel.close(native.fileSystem) },
        closeEndpoint = endpoint::close,
    )
    private val input = if (confined == null) DataInputStream(process.inputStream) else null
    private val output = if (confined == null) DataOutputStream(process.outputStream) else null

    override fun runConfined(exchange: ConfinedHelperJob) {
        checkNotNull(confined) { "Legacy helper cannot run a confined session" }
        lifetime.runConfinedIo {
            val transport = ConfinedHelperChannel(native, channel, {
                exchange.checkActive(ConfinedDeadline.now(native))
                !lifetime.accepting
            }, { process.isAlive })
            val io = ConfinedHelperProcessIo(transport, exchange, ::validateConfinedSetup, { capability, owner ->
                check(observations.observeRoot(owner.handle) == capability.identity) { "Transferred helper root differs from manifest" }
            }, { request, owner ->
                val key = observations.observeReadable(owner.handle, native)
                when (request.purpose) {
                    HelperPurpose.Application -> Unit
                    HelperPurpose.RuntimeSupport -> check(
                        key == exchange.manifest.capabilities[request.index]
                        .identity.key,
                    ) { "Helper support result identity mismatch" }
                }
            })
            while (true) {
                exchange.checkActive(ConfinedDeadline.now(native))
                val ticket = exchange.poll(ConfinedDeadline.now(native))
                if (ticket != null) {
                    io.execute(ticket)
                } else {
                    check(transport.pollFrame(exchange.operationDeadline) == null) { "Unsolicited helper response" }
                }
            }
        }
    }

    private fun validateConfinedSetup(manifest: ConfinedManifest) {
        val startup = requireNotNull(confined)
        try {
            val parent = observations.observeAncestry(startup.parent.identity.pid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
            val child = observations.observeAncestry(startup.child.identity.pid, Tid(startup.child.identity.pid.value), HelperSecurityObservation.Lineage.Bootstrap)
            check(parent.identity == startup.parent.identity && child.identity == startup.child.identity)
            startup.parent.time.requireCommon(parent.time)
            parent.time.requireCommon(child.time)
            check(parent.ancestry.canonical == startup.parent.ancestry.canonical && child.ancestry.canonical == startup.child.ancestry.canonical)
            check(parent.ancestry.canonical == child.ancestry.canonical)
            observations.requireSetupOwner(manifest, parent)
        } catch (expectedObservationFailure: Exception) {
            throw ConfinedHelperRejection.classify(expectedObservationFailure, ConfinedRejectReason.Ancestry)
        }
    }

    // SUPPRESSION-RATIONALE: Every private frame identity and descriptor receipt is checked before handoff; distinct failures retain their protocol boundary.
    override fun executeAuthority(job: TraceeAuthorityJob): SupervisorReplyEffect {
        check(confined == null) { "Confined helper refuses legacy authority" }
        val input = requireNotNull(input)
        val output = requireNotNull(output)
        check(lifetime.accepting) { "Socket helper is stopping or closed" }
        val notification = job.notification
        output.writeByte(TraceeAuthorityProtocol.OPERATION)
        TraceeAuthorityProtocol.writeRequest(output, notification, job.bypassRoots)
        if (input.readLong() != notification.id) throw IOException("Authority snapshot identity mismatch")
        job.prepared.complete(TraceeAuthorityProtocol.readSnapshot(input))
        val decision = job.decision.get(job.deadline.remainingMillis().toLong(), TimeUnit.MILLISECONDS)
        TraceeAuthorityProtocol.writeDecision(output, decision)
        if (input.readLong() != notification.id) throw IOException("Authority result identity mismatch")
        return TraceeAuthorityProtocol.readEffect(input) {
            val fd = RealSocketManager.recvDescriptor(channel, FileDescriptorRole.Generic) ?: throw IOException("Missing authority descriptor")
            SupervisorOwnedFd(fd, LinuxNative.fileSystem)
        }
    }

    override fun execute(
        operation: SocketOperation,
        socket: SupervisorOwnedFd,
    ): SocketHelperResult {
        check(confined == null) { "Confined helper refuses legacy socket operations" }
        val input = requireNotNull(input)
        val output = requireNotNull(output)
        check(lifetime.accepting) { "Socket helper is stopping or closed" }
        SocketHelperProtocol.writeOperation(output, operation)
        if (!RealSocketManager.sendDescriptor(channel, socket.handle)) throw IOException("Helper descriptor send failed")
        return SocketHelperProtocol.readResult(input) {
            val fd = RealSocketManager.recvDescriptor(channel, FileDescriptorRole.Generic) ?: throw IOException("Missing accepted descriptor")
            SupervisorOwnedFd(fd, LinuxNative.fileSystem)
        }
    }

    override fun close() = lifetime.close()

    companion object {
        fun startConfined(
            classpath: String = System.getProperty("java.class.path"),
            observations: HelperSecurityObservation = HelperSecurityObservation(),
            launch: (ProcessBuilder) -> Process = { it.start() },
        ): SocketHelperProcess {
            check(!Thread.currentThread().isVirtual) { "Confined helper must start on a platform thread" }
            require(
                classpath.split(java.io.File.pathSeparator).all {
                    it.isNotEmpty() &&
                        java.nio.file.Files
                    .isRegularFile(
                        java.nio.file.Path
                        .of(it),
                    )
                },
            ) { "Confined runtime needs exact classpath files" }
            return start(classpath, observations, launch)
        }

        fun start(): SocketHelperProcess = start(null)

        @Suppress("TooGenericExceptionCaught") // Acquisition transaction rolls back every acquired owner before rethrowing.
        private fun start(
            confinedClasspath: String?,
            observations: HelperSecurityObservation = HelperSecurityObservation(),
            launch: (ProcessBuilder) -> Process = { it.start() },
        ): SocketHelperProcess {
            val native = LinuxNative.snapshot()
            val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-socket-", "helper.sock")
            var process: Process? = null
            var server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
            var channel: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
            try {
                server = RealSocketManager.createUnixServer(endpoint.path)
                val command = JvmChildProcess.commandLine(
                    JvmChildSpec(
                    SocketHelperMain::class.java.name,
                        listOf(endpoint.path, ProcessHandle.current().pid().toString()) + if (confinedClasspath == null) emptyList() else listOf("--confined-read-v1"),
                        javaAgents = JavaAgentSelection.None,
                        classpath = confinedClasspath,
                        extraJvmArgs = if (confinedClasspath == null) emptyList() else listOf("-XX:-UsePerfData"),
                ),
                )
                val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT)
                if (confinedClasspath != null) builder.redirectInput(java.io.File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                val parent = if (confinedClasspath == null) {
                    null
                } else {
                    val pid = Pid(ProcessHandle.current().pid().toInt())
                    val bootstrap = observations.observeAncestry(pid, Tid(pid.value), HelperSecurityObservation.Lineage.Bootstrap)
                    observations.observeAncestry(pid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap).also { actual ->
                        bootstrap.time.requireCommon(actual.time)
                        check(bootstrap.identity == actual.identity && bootstrap.ancestry.canonical == actual.ancestry.canonical) { "Helper spawning thread differs from bootstrap" }
                    }
                }
                process = launch(builder)
                awaitConnection(server)
                channel = RealSocketManager.accept(server)
                io.mazewall.ffi.networking.SupervisorHelperCredentials
                    .verify(LinuxNative, channel, process.pid())
                val confined = parent?.let {
                    val childPid = Pid(process.pid().toInt())
                    val child = observations.observeAncestry(childPid, Tid(childPid.value), HelperSecurityObservation.Lineage.Bootstrap)
                    observations.requirePeer(
                        io.mazewall.ffi.networking.SupervisorHelperCredentials
                        .observe(native, channel),
                            child,
                    )
                    ConfinedStartup(it, child).also(ConfinedStartup::requireCommon)
                }
                RealSocketManager.close(server)
                server = null
                return SocketHelperProcess(process, channel, endpoint, native, confined, observations)
            } catch (failure: Throwable) {
                rollbackStartup(failure, process, channel, server, endpoint, native)
            }
        }

        internal fun rollbackStartup(
            failure: Throwable,
            process: Process?,
            channel: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?,
            server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>?,
            endpoint: PrivateUnixEndpoint,
            native: NativeEngine,
        ): Nothing {
            val lifetime = SocketHelperLifetime(
                reap = { process?.let(::terminate) },
                closeChannel = {
                    val channels = SocketCleanup()
                    channels.attempt { channel?.close(native.fileSystem) }
                    channels.attempt { server?.close(native.fileSystem) }
                    channels.rethrow()
                },
                closeEndpoint = endpoint::close,
            )
            val retirement = SocketCleanup()
            retirement.attempt(lifetime::close)
            val cleanup = SocketCleanup(failure)
            retirement.failure?.let { incomplete ->
                cleanup.attempt { throw incomplete }
                requireNotNull(cleanup.failure).addSuppressed(SocketHelperStartupRetirement(lifetime))
            }
            cleanup.rethrow()
            throw failure
        }

        private fun awaitConnection(server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
                NativeArena.ofConfined().use { arena ->
                    val poll = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
                    poll.setFd(server.value)
                    poll.setEvents(NativeConstants.POLLIN)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (true) {
                        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
                        if (left == 0L) throw IOException("Socket helper startup timed out")
                    if (pollReady(poll.managed, left.toInt())) break
                    }
                }
        }

        private fun pollReady(
            poll: io.mazewall.ffi.memory.ManagedSegment,
            timeout: Int,
        ): Boolean =
            when (val result = LinuxNative.raw.poll(poll, 1, timeout)) {
                is LinuxNative.SyscallResult.Success -> {
                    if (result.value == 0L) throw IOException("Socket helper startup timed out")
                    true
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno != NativeConstants.EINTR) throw IOException("Helper startup poll errno=${result.errno}")
                    false
                }
            }

        private fun terminate(process: Process) {
            var interrupted = false
            process.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            try {
                while (process.isAlive) {
                    val left = deadline - System.nanoTime()
                    check(left > 0) { "Socket helper could not be reaped" }
                    try {
                        process.waitFor(left, TimeUnit.NANOSECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}

/** Failed acquisition keeps its cleanup owner reachable without replacing the primary/fatal cause. */
internal class SocketHelperStartupRetirement(
    private val lifetime: SocketHelperLifetime,
) : RuntimeException("Socket helper startup retirement is incomplete", null, false, false),
    SocketHelper {
    override fun execute(
        operation: SocketOperation,
        socket: SupervisorOwnedFd,
    ): SocketHelperResult = error("Socket helper startup failed; only retirement is permitted")

    override fun close() = lifetime.close()
}

/** The retained pool job alone executes these helper-socket effects. */
internal class ConfinedHelperProcessIo(
    private val channel: ConfinedHelperChannel,
    private val exchange: ConfinedHelperJob,
    private val validateSetup: (ConfinedManifest) -> Unit,
    private val validateRoot: (HelperCapability, SupervisorOwnedFd) -> Unit,
    private val validateResult: (ConfinedReadRequest, SupervisorOwnedFd) -> Unit,
) {
    fun execute(ticket: ConfinedHelperJob.Ticket) {
        val binding = exchange.manifest.binding
        when (val command = ticket.command) {
            is Command.BeginSetup -> {
                validateSetup(command.manifest)
                channel.sendFrame(Frame.Setup(command.manifest, command.deadline))
                expect(Frame.Control(ControlTag.SetupAccepted, binding, command.deadline))
                exchange.acknowledge(ticket, ConfinedStage.SetupAccepted(binding))
            }
            is Command.OfferRoot -> {
                validateRoot(exchange.manifest.capabilities[command.index], command.owner)
                channel.sendFrame(Frame.Root(RootTag.Offer, binding, command.index, command.deadline))
                expect(Frame.Root(RootTag.Expected, binding, command.index, command.deadline))
                channel.sendDescriptor(command.owner, command.deadline)
                expect(Frame.Root(RootTag.Received, binding, command.index, command.deadline))
                command.owner.close()
                exchange.acknowledge(ticket, ConfinedStage.RootAdopted(binding, command.index))
            }
            is Command.FinishSetup -> {
                channel.sendFrame(Frame.Root(RootTag.Complete, binding, command.count, command.deadline))
                expect(Frame.Control(ControlTag.Ready, binding, command.deadline))
                exchange.ready(ticket)
            }
            is Command.BeginRead -> read(ticket, command)
            is Command.ResultForwarded -> exchange.acknowledge(ticket, ConfinedStage.Forwarded(binding, command.id))
            is Command.UpstreamReceived -> exchange.acknowledge(ticket, ConfinedStage.UpstreamAccepted(binding, command.id))
            is Command.IntermediateClosed -> {
                exchange.acknowledge(ticket, ConfinedStage.IntermediateCommitted(binding, command.id))
                commit(command.id, command.deadline)
            }
            is Command.Cancel -> exchange.cancel(command.cause)
        }
    }

    private fun read(
        ticket: ConfinedHelperJob.Ticket,
        command: Command.BeginRead,
    ) {
        val request = command.request
        val deadline = command.deadline
        channel.sendFrame(Frame.Read(request, deadline))
        val response = receive(deadline)
        if (response is Frame.Denied) {
            check(response.binding == request.binding && response.id == request.id && response.deadline == deadline) { "Confined denial identity mismatch" }
            exchange.denied(ticket, response.errno)
            return
        }
        check(response == Frame.Result(ResultTag.Opened, request.binding, request.id, deadline)) { "Confined OPENED identity mismatch" }
        channel.sendFrame(Frame.Result(ResultTag.Expected, request.binding, request.id, deadline))
        val owner = channel.receiveDescriptor(deadline)
        var transferred = false
        try {
            validateResult(request, owner)
            transferred = true
            exchange.quarantine(ticket, owner)
        } finally {
            if (!transferred) owner.close()
        }
        when (exchange.delivery) {
            ConfinedDelivery.Direct -> commit(request.id, deadline)
            ConfinedDelivery.Relay -> Unit
        }
    }

    private fun commit(
        id: Long,
        deadline: ConfinedDeadline,
    ) {
        exchange.commitResult(id, deadline)
        channel.sendFrame(Frame.Result(ResultTag.Received, exchange.manifest.binding, id, deadline))
        expect(Frame.Result(ResultTag.Done, exchange.manifest.binding, id, deadline))
        exchange.done(id, deadline)
    }

    private fun expect(expected: Frame) {
        check(receive(expected.deadline) == expected) { "Confined helper response phase or identity mismatch" }
    }

    private fun receive(deadline: ConfinedDeadline): Frame {
        val frame = channel.receiveFrame(deadline).frame
        if (frame is Frame.Rejected) {
            check(frame.binding == exchange.manifest.binding && frame.deadline == deadline) { "Confined rejection identity mismatch" }
            throw ConfinedHelperRejection(frame.reason, frame.errno)
        }
        return frame
    }
}
