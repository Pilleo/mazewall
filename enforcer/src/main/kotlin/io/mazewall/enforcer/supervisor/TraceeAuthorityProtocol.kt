package io.mazewall.enforcer.supervisor

import io.mazewall.core.NewFdFlags
import io.mazewall.core.Tid
import io.mazewall.platform.seccomp.SeccompNotification
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private bounded frames. Only descriptor-producing terminal effects carry one SCM_RIGHTS fd. */
internal object TraceeAuthorityProtocol {
    const val OPERATION = 4
    private const val MAX_ROOTS = 1024
    private const val MAX_PATH = 4095

    fun writeRequest(
        output: DataOutputStream,
        notification: SeccompNotification,
        roots: List<String>,
    ) {
        require(notification.args.size == 6 && roots.size <= MAX_ROOTS)
        output.writeLong(notification.id)
        output.writeInt(notification.pid)
        output.writeInt(notification.arch)
        output.writeInt(notification.nr)
        notification.args.forEach(output::writeLong)
        output.writeInt(roots.size)
        roots.forEach { writePath(output, it) }
        output.flush()
    }

    fun readRequest(input: DataInputStream): Pair<SeccompNotification, List<String>> {
        val notification = SeccompNotification(input.readLong(), input.readInt(), input.readInt(), input.readInt(), LongArray(6) { input.readLong() })
        if (notification.pid <= 0) throw IOException("Invalid authority tracee identity")
        val count = bounded(input.readInt(), 0..MAX_ROOTS, "bypass roots")
        val roots = List(count) { readPath(input) ?: throw IOException("Null bypass root") }
        if (roots.any { !it.startsWith('/') }) throw IOException("Relative bypass root")
        return notification to roots
    }

    fun writeSnapshot(
        output: DataOutputStream,
        snapshot: TraceeAuthoritySnapshot,
    ) {
        output.writeInt(snapshot.ppid)
        writePath(output, snapshot.path)
        writePath(output, snapshot.resolvedPath)
        writeRoute(output, snapshot.route)
        SocketHelperProtocol.writeBytes(output, snapshot.address ?: byteArrayOf())
        output.flush()
    }

    fun readSnapshot(input: DataInputStream): TraceeAuthoritySnapshot =
        TraceeAuthoritySnapshot(input.readInt(), readPath(input), readPath(input), readRoute(input), SocketHelperProtocol.readBytes(input).takeIf { it.isNotEmpty() })

    fun writeDecision(
        output: DataOutputStream,
        decision: TraceeAuthorityDecision,
    ) {
        writeRoute(output, decision.route)
        writePath(output, decision.path)
        output.flush()
    }

    fun readDecision(input: DataInputStream): TraceeAuthorityDecision = TraceeAuthorityDecision(readRoute(input), readPath(input))

    private fun writeRoute(
        output: DataOutputStream,
        route: SupervisorRoute,
    ) {
        when (route) {
            SupervisorRoute.Continue -> output.writeByte(0)
            SupervisorRoute.AskJvm -> output.writeByte(1)
            SupervisorRoute.InjectFd -> output.writeByte(2)
            SupervisorRoute.SecureExec -> output.writeByte(3)
            is SupervisorRoute.Abort -> {
                output.writeByte(4)
                output.writeInt(route.errno)
            }
        }
    }

    private fun readRoute(input: DataInputStream): SupervisorRoute =
        when (input.readUnsignedByte()) {
        0 -> SupervisorRoute.Continue
        1 -> SupervisorRoute.AskJvm
        2 -> SupervisorRoute.InjectFd
        3 -> SupervisorRoute.SecureExec
        4 -> SupervisorRoute.Abort(bounded(input.readInt(), 1..4095, "errno"), "authority preparation")
        else -> throw IOException("Invalid authority route")
    }

    fun writeEffect(
        output: DataOutputStream,
        effect: SupervisorReplyEffect,
        send: (SupervisorOwnedFd) -> Unit,
    ) {
        val descriptor = when (effect) {
            SupervisorReplyEffect.Continue -> {
                output.writeByte(0)
                null
            }
            is SupervisorReplyEffect.Error -> {
                output.writeByte(1)
                output.writeInt(effect.errno)
                null
            }
            is SupervisorReplyEffect.Value -> {
                output.writeByte(2)
                output.writeLong(effect.value)
                null
            }
            is SupervisorReplyEffect.AddFdAndReply -> {
                output.writeByte(3)
                output.writeInt(effect.flags.value)
                effect.descriptor
            }
            is SupervisorReplyEffect.SecureExecAndReply -> {
                output.writeByte(4)
                output.writeInt(effect.request.tid.value)
                output.writeLong(effect.request.argv)
                output.writeLong(effect.request.envp)
                output.writeLong(effect.request.execveat)
                output.writeLong(checkNotNull(effect.capturedEmptyPath))
                when (val target = effect.target) {
                    is SupervisorDescriptorResult.Denied -> {
                        output.writeInt(target.errno)
                        null
                    }
                    is SupervisorDescriptorResult.Opened -> {
                        output.writeInt(0)
                        target.descriptor
                    }
                }
            }
            is SupervisorReplyEffect.UnsupportedAuthority -> {
                output.writeByte(5)
                null
            }
            is SupervisorReplyEffect.AcceptedSocket, is SupervisorReplyEffect.Recycle -> throw IOException("Invalid authority terminal effect")
        }
        output.flush()
        descriptor?.let(send)
    }

    fun readEffect(
        input: DataInputStream,
        receive: () -> SupervisorOwnedFd,
    ): SupervisorReplyEffect =
        when (input.readUnsignedByte()) {
        0 -> SupervisorReplyEffect.Continue
        1 -> SupervisorReplyEffect.Error(bounded(input.readInt(), 1..4095, "errno"))
        2 -> SupervisorReplyEffect.Value(input.readLong())
        3 -> {
            val flags = input.readInt()
            if (flags != 0 && flags != 524288) throw IOException("Invalid injected fd flags")
            SupervisorReplyEffect.AddFdAndReply(receive(), NewFdFlags(flags))
        }
        4 -> {
            val request = SecureExecRequest(Tid(input.readInt()), input.readLong(), input.readLong(), input.readLong())
            val nul = input.readLong()
            val errno = bounded(input.readInt(), 0..4095, "exec errno")
            val target = if (errno == 0) SupervisorDescriptorResult.Opened(receive()) else SupervisorDescriptorResult.Denied(errno)
            SupervisorReplyEffect.SecureExecAndReply(request, target, nul)
        }
        5 -> SupervisorReplyEffect.UnsupportedAuthority(UnsupportedTraceeAuthority.AcceptPeerMemory)
        else -> throw IOException("Invalid authority effect")
    }

    fun writePath(
        output: DataOutputStream,
        path: String?,
    ) {
        val bytes = path?.toByteArray(Charsets.UTF_8)
        require(bytes == null || (bytes.size <= MAX_PATH && '\u0000' !in path))
        output.writeInt(bytes?.size ?: -1)
        bytes?.let(output::write)
    }

    fun readPath(input: DataInputStream): String? {
        val length = bounded(input.readInt(), -1..MAX_PATH, "path length")
        if (length < 0) return null
        val bytes = ByteArray(length).also(input::readFully)
        val path = Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        if ('\u0000' in path) throw IOException("NUL in authority path")
        return path
    }

    private fun bounded(
        value: Int,
        range: IntRange,
        label: String,
    ): Int {
        if (value !in range) throw IOException("Invalid authority $label: $value")
        return value
    }
}
