package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.JavaAgentSelection
import io.mazewall.core.JvmChildProcess
import io.mazewall.core.JvmChildSpec
import io.mazewall.core.PrivateUnixEndpoint
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
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
