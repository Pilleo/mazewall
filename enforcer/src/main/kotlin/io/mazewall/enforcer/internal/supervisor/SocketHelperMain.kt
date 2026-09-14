package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.socket.RealSocketManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** Private child entry point; receives only an already authorized snapshot and an owned SCM_RIGHTS duplicate. */
internal object SocketHelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        SocketHelperOwner.watch(args[1].toLong())
        val channel = RealSocketManager.connect(args[0])
        try {
            io.mazewall.ffi.networking.SupervisorHelperCredentials
                .verify(LinuxNative, channel, args[1].toLong())
            val input = DataInputStream(System.`in`)
            val output = DataOutputStream(System.out)
            while (true) {
                val tag = try {
                    input.readUnsignedByte()
                } catch (_: EOFException) {
                    return
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
        } finally {
            RealSocketManager.close(channel)
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
