package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.SocketIo
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.SupervisorResponseSegment
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.supervisor.JvmVerdictRequestEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal sealed interface JvmExchangeResult {
    val cleanupFailure: Exception? get() = null

    data class Verdict(
        val route: SupervisorRoute,
        val path: String?,
    ) : JvmExchangeResult

    data class Timeout(
        override val cleanupFailure: Exception? = null,
    ) : JvmExchangeResult

    data class Malformed(
        val reason: String,
        override val cleanupFailure: Exception? = null,
    ) : JvmExchangeResult

    data class TransportFailed(
        val errno: Int,
        override val cleanupFailure: Exception? = null,
    ) : JvmExchangeResult
}

/** The existing fixed-size JVM protocol, with one deadline and no kernel-reply capability. */
internal class JvmVerdictExchange(
    private val engine: NativeEngine,
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val sockets: SocketManager,
) {
    context(arena: NativeArena) fun send(request: JvmVerdictRequest): Boolean {
        val header = request.header
        val encoded = JvmVerdictRequestEncoder.encode(
            request.id,
            header.tid.value,
            header.audit,
            header.ppid,
            header.nr,
            header.args,
            request.path,
            request.sockaddrBytes,
        )
        return SocketIo.writeFully(engine.memory, socket, encoded.buffer, encoded.size) is LinuxNative.SyscallResult.Success
    }

    context(arena: NativeArena) fun receive(request: JvmVerdictRequest): JvmExchangeResult {
        val poll = PollFdSegment.of(arena.allocate(Layouts.POLLFD))
        poll.setFd(socket.value)
        poll.setEvents(NativeConstants.POLLIN)
        val buffer = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
        val deadline = Deadline.afterMillis(TIMEOUT_MILLIS)
        val pollBuffer = poll.managed
        val result = SocketIo.readFully(engine.memory, socket, buffer, Layouts.SUPERVISOR_RESPONSE_SIZE, deadline) { timeout ->
            if (Thread.currentThread().isInterrupted) {
                LinuxNative.SyscallResult.Error(NativeConstants.EIO, -1L)
            } else {
                engine.raw.poll(pollBuffer, 1L, timeout)
            }
        }
        val decoded = when (result) {
            is LinuxNative.SyscallResult.Error ->
                if (result.errno == NativeConstants.ETIMEDOUT) JvmExchangeResult.Timeout() else JvmExchangeResult.TransportFailed(result.errno)
            is LinuxNative.SyscallResult.Success -> decode(request, buffer)
        }
        return closeUnusableExchange(decoded)
    }

    /** Cleanup evidence accompanies, and can never replace, the unusable exchange outcome. */
    private fun closeUnusableExchange(result: JvmExchangeResult): JvmExchangeResult {
        if (result is JvmExchangeResult.Verdict) return result
        val failure = try {
            close()
            null
        } catch (expectedCloseFailure: Exception) {
            expectedCloseFailure
        }
        return when (result) {
            is JvmExchangeResult.Timeout -> result.copy(cleanupFailure = failure)
            is JvmExchangeResult.Malformed -> result.copy(cleanupFailure = failure)
            is JvmExchangeResult.TransportFailed -> result.copy(cleanupFailure = failure)
            is JvmExchangeResult.Verdict -> result
        }
    }

    private fun decode(
        request: JvmVerdictRequest,
        buffer: ManagedSegment,
    ): JvmExchangeResult {
        val response = SupervisorResponseSegment.of(buffer)
        val pathLength = buffer.readInt(Layouts.SUPERVISOR_RESPONSE_PATH_LEN_OFFSET)
        if (pathLength !in 0..Layouts.SUPERVISOR_RESPONSE_PATH_CAP.toInt()) return JvmExchangeResult.Malformed("invalid exec path length: $pathLength")
        val route = SupervisorJvmVerdictResolver.route(
            request.id,
            request.header.nr,
            request.header.arch,
            response.getId(),
            response.getDecision().toInt(),
            response.getErrorNr(),
        ) ?: return JvmExchangeResult.Malformed("notification ID mismatch")
        val bytes = ByteArray(pathLength)
        ManagedSegment.copy(buffer, Layouts.SUPERVISOR_RESPONSE_PATH_OFFSET, bytes, 0, pathLength)
        val path = if (bytes.isEmpty()) null else decodePath(bytes)
        return if (bytes.isNotEmpty() && path == null) JvmExchangeResult.Malformed("invalid UTF-8 exec path") else JvmExchangeResult.Verdict(route, path)
    }

    private fun decodePath(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .takeIf { '\u0000' !in it }
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }

    fun close() {
        if (socket.isLiveForIo()) sockets.close(socket)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
