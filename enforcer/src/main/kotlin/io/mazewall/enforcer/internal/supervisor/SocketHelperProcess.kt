package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
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
internal class SocketHelperProcess private constructor(
    private val process: Process,
    private val channel: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val endpoint: PrivateUnixEndpoint,
) : SocketHelper {
    private val lifetime = SocketHelperLifetime(
        reap = { terminate(process) },
        closeChannel = { RealSocketManager.close(channel) },
        closeEndpoint = endpoint::close,
    )
    private val input = DataInputStream(process.inputStream)
    private val output = DataOutputStream(process.outputStream)

    // SUPPRESSION-RATIONALE: Every private frame identity and descriptor receipt is checked before handoff; distinct failures retain their protocol boundary.
    override fun executeAuthority(job: TraceeAuthorityJob): SupervisorReplyEffect {
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
        @Suppress("TooGenericExceptionCaught") // Acquisition transaction rolls back every acquired owner before rethrowing.
        fun start(): SocketHelperProcess {
            val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-socket-", "helper.sock")
            var process: Process? = null
            var server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
            var channel: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
            try {
                server = RealSocketManager.createUnixServer(endpoint.path)
                val command = JvmChildProcess.commandLine(
                    JvmChildSpec(
                    SocketHelperMain::class.java.name,
                        listOf(endpoint.path, ProcessHandle.current().pid().toString()),
                        javaAgents = JavaAgentSelection.None,
                ),
                )
                process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                awaitConnection(server)
                channel = RealSocketManager.accept(server)
                io.mazewall.ffi.networking.SupervisorHelperCredentials
                    .verify(LinuxNative, channel, process.pid())
                RealSocketManager.close(server)
                server = null
                return SocketHelperProcess(process, channel, endpoint)
            } catch (failure: Throwable) {
                val cleanup = SocketCleanup(failure)
                cleanup.attempt { process?.let(::terminate) }
                cleanup.attempt { channel?.let(RealSocketManager::close) }
                cleanup.attempt { server?.let(RealSocketManager::close) }
                cleanup.attempt { endpoint.close() }
                cleanup.rethrow()
                throw failure
            }
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
