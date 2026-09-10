package io.mazewall.portal

import io.mazewall.LinuxNative
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.NativeCloseFailure
import io.mazewall.core.OpenFlags
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.getFdOrThrow
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Thrown when a portal read deadline expires. Distinct from fatal socket errors so idle
 *  workers can keep polling (issue: pooled-worker 30s self-exit). */
public class PortalReadTimeoutException : java.io.IOException("portal read timed out")

public class PortalIncompleteFrameException : java.io.IOException("portal frame incomplete at deadline")

public class PortalChannel(
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val sockets: SocketManager = RealSocketManager,
) : AutoCloseable {
    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val closed = java.util.concurrent.atomic
        .AtomicBoolean()
    private val ioGate = ReentrantLock()
    private var socketRetired = false
    private val received = ArrayList<Capability.ReadFd>(PortalFrame.MAX_FDS)
    private val io = PortalIo(socket) { closed.get() }

    fun send(
        frame: PortalFrame,
        fds: List<FileDescriptor<*, FdState.Open, FdOwnership.Owned>> = emptyList(),
        deadline: Deadline = Deadline.afterMillis(DEFAULT_TIMEOUT_MS),
    ) {
        require(fds.size == frame.fdCount) { "fd list ${fds.size} != header ${frame.fdCount}" }
        terminalOnFailure(deadline) {
            writeFrame(frame, deadline)
            for (fd in fds) io.sendDescriptor(fd, deadline)
        }
    }

    internal fun sendCapabilities(
        frame: PortalFrame,
        capabilities: List<Capability.ReadFd>,
        deadline: Deadline,
    ) {
        require(capabilities.size == frame.fdCount)
        terminalOnFailure(deadline) {
            writeFrame(frame, deadline)
            capabilities.forEach { capability -> capability.borrowUntil(deadline, closed::get) { io.sendDescriptor(it, deadline) } }
        }
    }

    fun receive(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Pair<PortalFrame, List<Capability.ReadFd>> = receive(Deadline.afterMillis(timeoutMs))

    fun receive(deadline: Deadline): Pair<PortalFrame, List<Capability.ReadFd>> {
        return terminalOnFailure(deadline) {
            val header = PortalFrame.parseHeader(io.read(PortalFrame.HEADER_SIZE, deadline, idleAllowed = true))
            val payload = io.read(header.payloadLen, deadline, idleAllowed = false)
            repeat(header.fdCount) { received.add(io.receiveDescriptor(deadline)) }
            val delivery = PortalFrame(header.kind, header.requestId, header.method, PortalPayload(payload), header.fdCount) to received.toList()
            received.clear() // Transfer ownership only after every allocation for delivery succeeded.
            delivery
        }
    }

    @Suppress("TooGenericExceptionCaught") // Drain every independent owner and retain unfinished retirement for another close.
    override fun close() {
        // Signal first so bounded polling leaves the I/O gate before releasing the integer.
        closed.set(true)
        ioGate.withLock {
            var failure: Throwable? = null
            try {
                Capability.closeAll(received)
            } catch (cleanup: Throwable) {
                failure = cleanup
            } finally {
                received.removeAll { it.isRetired }
            }
            if (!socketRetired) {
                try {
                    retireSocket()
                } catch (cleanup: Throwable) {
                    failure = failure?.let { preferFatal(it, cleanup) } ?: cleanup
                }
            }
            failure?.let { throw it }
        }
    }

    private fun retireSocket() {
        try {
            sockets.close(socket)
            socketRetired = true
        } catch (nativeFailure: NativeCloseFailure) {
            socketRetired = true // A completed native close must never retry a possibly reused integer.
            throw nativeFailure
        }
    }

    private fun writeFrame(
        frame: PortalFrame,
        deadline: Deadline,
    ) {
        io.write(frame.headerBytes(), deadline)
        io.write(frame.payload.copyToByteArray(), deadline)
    }

    // All partial transfers invalidate framing. The distinct idle, primary, and cleanup throws
    // preserve protocol classification and fatal-failure precedence at this terminal boundary.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> terminalOnFailure(
        deadline: Deadline,
        action: () -> T,
    ): T {
        check(!closed.get()) { "portal channel is closed" }
        PortalAdmission.acquire(ioGate, deadline, closed::get)
        try {
            check(!closed.get()) { "portal channel is closed" }
            return action()
        } catch (idle: PortalReadTimeoutException) {
            raisePortalFailure(idle)
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                raisePortalFailure(preferFatal(failure, cleanup))
            }
            raisePortalFailure(failure)
        } finally {
            ioGate.unlock()
        }
    }

    private fun preferFatal(
        first: Throwable,
        next: Throwable,
    ): Throwable {
        if (next is Error && first !is Error) {
            next.addSuppressed(first)
            return next
        }
        if (first !== next) first.addSuppressed(next)
        return first
    }
}

private fun raisePortalFailure(failure: Throwable): Nothing = throw failure

internal fun openGrantedRead(
    rootDir: Path,
    relative: String,
): Capability.ReadFd {
    require(relative.isNotEmpty()) { "relative path required" }
    require(!relative.startsWith("/")) { "absolute paths are not granted; pass a path relative to root" }
    require('\u0000' !in relative) { "NUL in path" }

    NativeArena.ofConfined().use { arena ->
        val rootFlags =
            OpenFlags(
                NativeConstants.O_RDONLY or NativeConstants.O_DIRECTORY or NativeConstants.O_CLOEXEC,
            )
        val rootFd =
            LinuxNative.fileSystem
                .open(arena.allocateFrom(rootDir.toAbsolutePath().toString()), rootFlags)
                .getFdOrThrow("open grant root")
        try {
            val how = arena.allocate(Layouts.OPEN_HOW_SIZE)
            how.writeLong(Layouts.OPEN_HOW_FLAGS_OFFSET, (NativeConstants.O_RDONLY or NativeConstants.O_CLOEXEC).toLong())
            how.writeLong(Layouts.OPEN_HOW_MODE_OFFSET, 0L)
            how.writeLong(Layouts.OPEN_HOW_RESOLVE_OFFSET, NativeConstants.RESOLVE_BENEATH)
            when (
                val opened =
                    LinuxNative.fileSystem.openat2(
                        rootFd,
                        arena.allocateFrom(relative),
                        how,
                        Layouts.OPEN_HOW_SIZE,
                    )
            ) {
                is LinuxNative.SyscallResult.Error -> {
                    check(opened.errno != NativeConstants.ENOSYS) {
                        "openat2 is required for granted FDs (fail closed)"
                    }
                    opened.throwErrno("openat2 RESOLVE_BENEATH $relative")
                }

                is LinuxNative.SyscallResult.Success -> {
                    val granted = opened.getFdOrThrow("open grant", FileDescriptorRole.Granted)
                    return Capability.ReadFd(granted)
                }
            }
        } finally {
            LinuxNative.fileSystem.close(rootFd)
        }
    }
}
