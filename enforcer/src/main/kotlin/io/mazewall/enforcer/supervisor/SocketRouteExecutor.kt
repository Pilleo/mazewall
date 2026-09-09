package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.NewFdFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorProcessMemoryReader
import io.mazewall.ffi.memory.SupervisorProcessMemoryWriter
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AcceptRequest(
    val tid: Tid,
    val fd: Int,
    val address: Long,
    val addressLength: Long,
    val flags: Int,
)

/** Socket I/O produces owned resources or errno effects; it has no kernel-reply capability. */
internal class SocketRouteExecutor(
    private val engine: NativeEngine,
    private val descriptors: TraceeDescriptorImporter,
    private val peerAddress: SupervisorPeerAddress = SupervisorPeerAddress(),
) {
    context(arena: NativeArena) fun connect(
        tid: Tid,
        fd: Int,
        address: ByteArray,
    ): SupervisorReplyEffect {
        if (address.size < Short.SIZE_BYTES) return SupervisorReplyEffect.Error()
        return when (val socket = descriptors.import(tid, fd)) {
            is SupervisorDescriptorResult.Denied -> SupervisorReplyEffect.Error(socket.errno)
            is SupervisorDescriptorResult.Opened -> connectOwned(socket.descriptor, address)
        }
    }

    context(arena: NativeArena) private fun connectOwned(
        socket: SupervisorOwnedFd,
        address: ByteArray,
    ): SupervisorReplyEffect {
        try {
            val bytes = arena.allocate(address.size.toLong())
            ManagedSegment.copy(address, 0, bytes, 0, address.size)
            return when (val result = engine.networking.connect(socket.handle, bytes, address.size)) {
                is LinuxNative.SyscallResult.Error -> SupervisorReplyEffect.Error(result.errno)
                is LinuxNative.SyscallResult.Success -> SupervisorReplyEffect.Value(0)
            }
        } finally {
            socket.close()
        }
    }

    context(arena: NativeArena) fun accept(request: AcceptRequest): SupervisorReplyEffect =
        when (val socket = descriptors.import(request.tid, request.fd)) {
            is SupervisorDescriptorResult.Denied -> SupervisorReplyEffect.Error(socket.errno)
            is SupervisorDescriptorResult.Opened -> {
                var buffers: Pair<ManagedSegment, ManagedSegment>? = null
                val accepted = socket.descriptor.produce {
                    val address = arena.allocate(MAX_ADDRESS_LENGTH.toLong())
                    val length = arena.allocate(Int.SIZE_BYTES.toLong())
                    length.writeInt(0, MAX_ADDRESS_LENGTH)
                    buffers = address to length
                    SupervisorDescriptorResult.from(engine.networking.accept4(socket.descriptor.handle, address, length, request.flags), engine)
                }
                when (val client = accepted) {
                    is SupervisorDescriptorResult.Denied -> SupervisorReplyEffect.Error(client.errno)
                    is SupervisorDescriptorResult.Opened -> {
                        val (address, length) = checkNotNull(buffers)
                        prepareAccepted(client.descriptor, request, address, length.readInt(0))
                    }
                }
            }
        }

    context(arena: NativeArena) private fun prepareAccepted(
        client: SupervisorOwnedFd,
        request: AcceptRequest,
        address: ManagedSegment,
        length: Int,
    ): SupervisorReplyEffect {
        var transferred = false
        try {
            if (!peerAddress.copy(request, address, length)) return SupervisorReplyEffect.Error(NativeConstants.EFAULT)
            transferred = true
            return SupervisorReplyEffect.AddFdAndReply(client, NewFdFlags.forAccept(request.flags))
        } finally {
            if (!transferred) client.close()
        }
    }

    private companion object {
        const val MAX_ADDRESS_LENGTH = 128
    }
}

/** Copies only bounded peer bytes and reports failed tracee writes to the route executor. */
internal class SupervisorPeerAddress {
    context(arena: NativeArena) fun copy(
        request: AcceptRequest,
        address: ManagedSegment,
        actualLength: Int,
    ): Boolean {
        if (request.address == 0L || request.addressLength == 0L) return true
        val requested = SupervisorProcessMemoryReader
            .readBytes(request.tid, request.addressLength, Int.SIZE_BYTES)
            ?.takeIf { it.size == Int.SIZE_BYTES } ?: return false
        val requestedLength = ByteBuffer.wrap(requested).order(ByteOrder.nativeOrder()).int
        val copyLength = minOf(actualLength, requestedLength, address.byteSize().toInt())
        return copyLength >= 0 && copyBytes(request, address, actualLength, copyLength)
    }

    context(arena: NativeArena) private fun copyBytes(
        request: AcceptRequest,
        address: ManagedSegment,
        actualLength: Int,
        copyLength: Int,
    ): Boolean {
        val bytes = ByteArray(copyLength)
        ManagedSegment.copy(address, 0, bytes, 0, copyLength)
        val copied = copyLength == 0 || SupervisorProcessMemoryWriter.writeBytes(request.tid, request.address, bytes)
        val length = ByteBuffer
            .allocate(Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .putInt(actualLength)
            .array()
        return copied && SupervisorProcessMemoryWriter.writeBytes(request.tid, request.addressLength, length)
    }
}
