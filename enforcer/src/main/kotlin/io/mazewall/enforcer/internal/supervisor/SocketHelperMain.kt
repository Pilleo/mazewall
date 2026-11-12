package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import io.mazewall.ffi.networking.SupervisorHelperCredentials
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.socket.RealSocketManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** Private child entry point; receives only an already authorized snapshot and an owned SCM_RIGHTS duplicate. */
internal object SocketHelperMain {
    private const val CONFINED_ARGS = 3

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2 || args.size == CONFINED_ARGS && args[2] == "--confined-read-v1")
        SocketHelperOwner.watch(args[1].toLong())
        val channel = RealSocketManager.connect(args[0])
        val cleanup = SocketCleanup()
        cleanup.attempt {
            io.mazewall.ffi.networking.SupervisorHelperCredentials
                .verify(LinuxNative, channel, args[1].toLong())
            if (args.size == CONFINED_ARGS) {
                confined(channel, Pid(args[1].toInt()), LinuxNative.snapshot())
                return@attempt
            }
            val input = DataInputStream(System.`in`)
            val output = DataOutputStream(System.out)
            while (true) {
                val tag = try {
                    input.readUnsignedByte()
                } catch (_: EOFException) {
                    return@attempt
                }
                if (tag == TraceeAuthorityProtocol.OPERATION) {
                    TraceeAuthorityHelper.serve(input, output) { descriptor ->
                        if (!RealSocketManager.sendDescriptor(channel, descriptor.handle)) throw IOException("Authority descriptor send failed")
                    }
                    continue
                }
                val operation = SocketHelperProtocol.readOperation(input, tag)
                val result = receiveOperation(channel, operation)
                sendResult(output, channel, result)
            }
        }
        cleanup.attempt { RealSocketManager.close(channel) }
        cleanup.rethrow()
    }

    private fun confined(
        channel: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        parentPid: Pid,
        native: NativeEngine,
    ) {
        check(!Thread.currentThread().isVirtual) { "Confined helper operation thread must be a platform thread" }
        val observations = HelperSecurityObservation()
        val selfPid = Pid(ProcessHandle.current().pid().toInt())
        val startup = SocketHelperProcess.ConfinedStartup(
            observations.observeAncestry(parentPid, Tid(parentPid.value), HelperSecurityObservation.Lineage.Bootstrap),
            observations.observeAncestry(selfPid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap),
        )
        startup.requireCommon()
        observations.requirePeer(SupervisorHelperCredentials.observe(native, channel), startup.parent)
        val parent = ProcessHandle.current().parent().orElseThrow { IOException("Missing helper parent") }
        check(parent.pid() == parentPid.value.toLong() && parent.isAlive) { "Helper parent identity changed" }
        // ProcessHandle.isAlive reads proc; observe exit on the prestarted trusted JDK reaper instead.
        val parentExit = parent.onExit()
        val transport = ConfinedHelperChannel(native, channel, { false }, { !parentExit.isDone })

        fun validate(manifest: ConfinedManifest) {
                val current = observations.observeAncestry(selfPid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
                val observedParent = observations.observeAncestry(parentPid, Tid(parentPid.value), HelperSecurityObservation.Lineage.Bootstrap)
                 SocketHelperProcess.ConfinedStartup(observedParent, current).requireCommon()
                 startup.child.time.requireCommon(current.time)
                 check(current.identity == startup.child.identity && observedParent.identity == startup.parent.identity && current.ancestry.canonical == startup.child.ancestry.canonical)
                 observations.requireSetupOwner(manifest, current)
        }
        ConfinedHelperConfinement(native, observations, channel.value).use { confinement ->
            serveConfined(transport, confinement, observations, native, ::validate)
        }
    }

    internal fun serveConfined(
        transport: ConfinedHelperChannel,
        confinement: ConfinedHelperConfinement,
        observations: HelperSecurityObservation,
        native: NativeEngine,
        validate: (ConfinedManifest) -> Unit,
    ) {
        ConfinedSession(transport, confinement, observations, native, validate).serve()
    }

    private class ConfinedSession(
        private val transport: ConfinedHelperChannel,
        private val confinement: ConfinedHelperConfinement,
        private val observations: HelperSecurityObservation,
        private val native: NativeEngine,
        private val validate: (ConfinedManifest) -> Unit,
    ) {
        private var state: ConfinedHelperState = ConfinedHelperState.Unsealed
        private var localDeadline: ConfinedDeadline? = null
        private var result: SupervisorOwnedFd? = null
        private var binding: ConfinedBinding? = null
        private var wireDeadline: ConfinedDeadline? = null
        private var failureReason = ConfinedRejectReason.Malformed

        private fun transition(event: ConfinedHelperEvent) {
            val next = ConfinedHelperMachine.evaluate(state, event)
            state = next.state
            next.effects.forEach { effect ->
                val cap = requireNotNull(localDeadline)
                cap.remainingNanos(ConfinedDeadline.now(native))
                when (effect) {
                    is ConfinedHelperEffect.Send -> transport.sendFrame(effect.frame, cap)
                    is ConfinedHelperEffect.Validate -> {
                        failureReason = ConfinedRejectReason.Ancestry
                        validate(effect.manifest)
                        transition(ConfinedHelperEvent.SetupValidated(effect.manifest.binding, effect.deadline))
                    }
                    is ConfinedHelperEffect.ReceiveRoot -> {
                        failureReason = ConfinedRejectReason.Identity
                        confinement.adopt(effect.capability, transport.receiveDescriptor(cap))
                        val receiving = state as ConfinedHelperState.Receiving
                        transition(ConfinedHelperEvent.RootAdopted(receiving.manifest.binding, effect.capability.index, effect.deadline))
                    }
                    is ConfinedHelperEffect.Seal -> {
                        failureReason = ConfinedRejectReason.RuntimeClosure
                        confinement.seal(effect.manifest, cap) { validate(effect.manifest) }
                        transition(ConfinedHelperEvent.Sealed(effect.manifest.binding, effect.deadline))
                        localDeadline = null
                    }
                    is ConfinedHelperEffect.Open -> open(effect)
                    is ConfinedHelperEffect.SendResult -> {
                        transport.sendDescriptor(requireNotNull(result), cap)
                        transition(ConfinedHelperEvent.ResultSent((state as ConfinedHelperState.Ready).manifest.binding, effect.id, effect.deadline))
                    }
                    is ConfinedHelperEffect.CommitResult -> {
                        requireNotNull(result).close()
                        result = null
                        transition(ConfinedHelperEvent.ReadCompleted((state as ConfinedHelperState.Ready).manifest.binding, effect.id, effect.deadline))
                        localDeadline = null
                    }
                    is ConfinedHelperEffect.Stop -> {
                        confinement.close()
                        transition(ConfinedHelperEvent.Stopped((state as ConfinedHelperState.Stopping).binding, effect.deadline))
                    }
                    is ConfinedHelperEffect.Abort -> throw effect.cause
                }
            }
        }

        private fun open(effect: ConfinedHelperEffect.Open) {
            when (val opened = confinement.open(effect.request)) {
                is SupervisorDescriptorResult.Denied -> {
                    transition(ConfinedHelperEvent.Denied(effect.request.binding, effect.request.id, opened.errno, effect.deadline))
                    localDeadline = null
                }
                is SupervisorDescriptorResult.Opened -> {
                    result = opened.descriptor
                    observations.observeReadable(opened.descriptor.handle, native)
                    transition(ConfinedHelperEvent.Opened(effect.request.binding, effect.request.id, effect.deadline))
                }
            }
        }

        fun serve() {
            val cleanup = SocketCleanup()
            cleanup.attempt {
                while (state !is ConfinedHelperState.Terminal) {
                    val received = transport.pollFrame(localDeadline) ?: continue
                    localDeadline = received.localDeadline
                    if (state == ConfinedHelperState.Unsealed && received.frame is ConfinedHelperProtocol.Frame.Setup) {
                        binding = received.frame.binding
                        wireDeadline = received.frame.deadline
                    } else if (state is ConfinedHelperState.Ready && (state as ConfinedHelperState.Ready).active == null) {
                        wireDeadline = received.frame.deadline
                    }
                    failureReason = ConfinedRejectReason.Malformed
                    transition(ConfinedHelperEvent.ParentFrame(received.frame))
                }
            }
            val failure = cleanup.failure
            if (failure != null && binding != null && wireDeadline != null) {
                val rejection = ConfinedHelperRejection.classify(failure, failureReason)
                cleanup.attempt {
                    transport.sendFrame(ConfinedHelperProtocol.Frame.Rejected(requireNotNull(binding), rejection.reason, rejection.errno, requireNotNull(wireDeadline)), requireNotNull(localDeadline))
                }
            }
            cleanup.attempt { result?.close() }
            cleanup.rethrow()
        }
    }

    private fun receiveOperation(
        channel: io.mazewall.platform.fd.FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
        operation: SocketOperation,
    ): SocketHelperResult {
                val descriptor = RealSocketManager.recvDescriptor(channel, FileDescriptorRole.Generic) ?: throw IOException("Missing operation descriptor")
        return SupervisorOwnedFd(descriptor, LinuxNative.fileSystem).use { socket ->
                    NativeArena.ofConfined().use { arena -> with(arena) { SocketOperationExecutor(LinuxNative).execute(operation, socket) } }
                }
    }

    // SUPPRESSION-RATIONALE: Each exhaustive effect branch validates its own transfer; finally disposes the effect on every failed write or descriptor send.
    private fun sendResult(
        output: DataOutputStream,
        channel: io.mazewall.platform.fd.FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
        result: SocketHelperResult,
    ) {
                try {
                    when (result) {
                        is SocketHelperResult.Authority -> throw IOException("Authority reply in socket protocol")
                        SocketHelperResult.Connected -> output.writeByte(0)
                        is SocketHelperResult.Failed -> {
                            output.writeByte(1)
                            output.writeInt(result.errno)
                        }
                        is SocketHelperResult.Accepted -> {
                            output.writeByte(2)
                            output.writeInt(result.length)
                            SocketHelperProtocol.writeBytes(output, result.address)
                            output.flush()
                            if (!RealSocketManager.sendDescriptor(channel, result.descriptor.handle)) throw IOException("Accepted descriptor send failed")
                        }
                        is SocketHelperResult.Opened -> {
                            output.writeByte(SocketHelperProtocol.OPENED_RESULT)
                            output.flush()
                            if (!RealSocketManager.sendDescriptor(channel, result.descriptor.handle)) throw IOException("Opened descriptor send failed")
                        }
                    }
                    output.flush()
                } finally {
                    result.dispose()
                }
    }
}

/** Native operation mechanism shared by the child and deterministic boundary tests. */
internal class SocketOperationExecutor(
    private val engine: NativeEngine,
) {
    @Suppress("TooGenericExceptionCaught") // Accepted successor remains owned until heap snapshot construction commits.
    context(arena: NativeArena) fun execute(
        operation: SocketOperation,
        socket: SupervisorOwnedFd,
    ): SocketHelperResult =
        when (operation) {
        is SocketOperation.Connect -> {
            val snapshot = operation.address
            val address = arena.allocate(snapshot.size.toLong())
            ManagedSegment.copy(snapshot, 0, address, 0, snapshot.size)
            when (val result = engine.networking.connect(socket.handle, address, snapshot.size)) {
                is LinuxNative.SyscallResult.Success -> SocketHelperResult.Connected
                is LinuxNative.SyscallResult.Error -> SocketHelperResult.Failed(result.errno)
            }
        }
        is SocketOperation.Accept -> {
            val address = arena.allocate(SocketHelperProtocol.MAX_ADDRESS_BYTES.toLong())
            val length = arena.allocate(Int.SIZE_BYTES.toLong())
            length.writeInt(0, SocketHelperProtocol.MAX_ADDRESS_BYTES)
            when (val accepted = SupervisorDescriptorResult.from(engine.networking.accept4(socket.handle, address, length, operation.flags), engine)) {
                is SupervisorDescriptorResult.Denied -> SocketHelperResult.Failed(accepted.errno)
                is SupervisorDescriptorResult.Opened -> {
                    try {
                        val size = length.readInt(0)
                        if (size !in 0..SocketHelperProtocol.MAX_ADDRESS_BYTES) throw IOException("Invalid kernel peer address length $size")
                        val bytes = ByteArray(size)
                        ManagedSegment.copy(address, 0, bytes, 0, size)
                        SocketHelperResult.Accepted(accepted.descriptor, bytes, size)
                    } catch (failure: Throwable) {
                        accepted.descriptor.use { throw failure }
                    }
                }
            }
        }
        is SocketOperation.OpenAt2 -> {
            val how = arena.allocate(Layouts.OPEN_HOW_SIZE)
            how.writeLong(Layouts.OPEN_HOW_FLAGS_OFFSET, operation.flags)
            how.writeLong(Layouts.OPEN_HOW_MODE_OFFSET, operation.mode)
            how.writeLong(Layouts.OPEN_HOW_RESOLVE_OFFSET, operation.resolve)
            when (
                val opened = SupervisorDescriptorResult.from(
                engine.fileSystem.openat2(socket.handle, arena.allocateFrom(operation.path), how, Layouts.OPEN_HOW_SIZE),
                    engine,
            )
            ) {
                is SupervisorDescriptorResult.Denied -> SocketHelperResult.Failed(opened.errno)
                is SupervisorDescriptorResult.Opened -> SocketHelperResult.Opened(opened.descriptor)
            }
        }
    }
}
