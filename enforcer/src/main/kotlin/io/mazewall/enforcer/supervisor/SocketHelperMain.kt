package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeInt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** Private child entry point; receives only an already authorized snapshot and an owned SCM_RIGHTS duplicate. */
internal object SocketHelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val channel = RealSocketManager.connect(args[0])
        try {
            val input = DataInputStream(System.`in`)
            val output = DataOutputStream(System.out)
            while (true) {
                val operation = try {
                    SocketHelperProtocol.readOperation(input)
                } catch (_: EOFException) {
                    return
                }
                val result = receiveOperation(channel, operation)
                sendResult(output, channel, result)
            }
        } finally {
            RealSocketManager.close(channel)
        }
    }

    private fun receiveOperation(
        channel: io.mazewall.core.FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, io.mazewall.core.FdOwnership.Owned>,
        operation: SocketOperation,
    ): SocketHelperResult {
                val descriptor = RealSocketManager.recvDescriptor(channel, FileDescriptorRole.Generic) ?: throw IOException("Missing operation descriptor")
        return SupervisorOwnedFd(descriptor, LinuxNative.fileSystem).use { socket ->
                    NativeArena.ofConfined().use { arena -> with(arena) { SocketOperationExecutor(LinuxNative).execute(operation, socket) } }
                }
    }

    private fun sendResult(
        output: DataOutputStream,
        channel: io.mazewall.core.FileDescriptor<FileDescriptorRole.UnixSocket, io.mazewall.core.FdState.Open, io.mazewall.core.FdOwnership.Owned>,
        result: SocketHelperResult,
    ) {
                try {
                    when (result) {
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
                    }
                    output.flush()
                } finally {
                    result.dispose()
                }
    }
}

/** Native operation mechanism shared by the child and deterministic boundary tests. */
internal class SocketOperationExecutor(
    private val engine: io.mazewall.NativeEngine,
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
    }
}
