package io.mazewall.portal

import io.mazewall.LinuxNative
import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.OpenFlags
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.getFdOrThrow
import java.nio.file.Path

/** Thrown when a portal read deadline expires. Distinct from fatal socket errors so idle
 *  workers can keep polling (issue: pooled-worker 30s self-exit). */
public class PortalReadTimeoutException : java.io.IOException("portal read timed out")

public class PortalIncompleteFrameException : java.io.IOException("portal frame incomplete at deadline")

public class PortalChannel(
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val sockets: SocketManager = RealSocketManager,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic
        .AtomicBoolean()
    private val ioGate = Any()
    private val io = PortalIo(socket) { closed.get() }

    fun send(
        frame: PortalFrame,
        fds: List<FileDescriptor<*, FdState.Open, FdOwnership.Owned>> = emptyList(),
        deadline: Deadline = Deadline.afterMillis(30_000),
    ) {
        require(fds.size == frame.fdCount) { "fd list ${fds.size} != header ${frame.fdCount}" }
        terminalOnFailure {
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
        terminalOnFailure {
            writeFrame(frame, deadline)
            capabilities.forEach { capability -> capability.borrowUntil(deadline) { io.sendDescriptor(it, deadline) } }
        }
    }

    fun receive(timeoutMs: Long = 30_000L): Pair<PortalFrame, List<Capability.ReadFd>> = receive(Deadline.afterMillis(timeoutMs))

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // Partial receipt owns all FDs; drain them even for fatal allocation failures.
    fun receive(deadline: Deadline): Pair<PortalFrame, List<Capability.ReadFd>> {
        val owned = ArrayList<Capability.ReadFd>(PortalFrame.MAX_FDS)
        return terminalOnFailure {
            try {
                val header = PortalFrame.parseHeader(io.read(PortalFrame.HEADER_SIZE, deadline, idleAllowed = true))
                val payload = io.read(header.payloadLen, deadline, idleAllowed = false)
                repeat(header.fdCount) { owned.add(io.receiveDescriptor(deadline)) }
                PortalFrame(header.kind, header.requestId, header.method, PortalPayload(payload), header.fdCount) to owned.toList()
            } catch (failure: Throwable) {
                try {
                    Capability.closeAll(owned)
                } catch (cleanup: Throwable) {
                    if (cleanup is Error && failure !is Error) {
                        cleanup.addSuppressed(failure)
                        throw cleanup
                    }
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }

    override fun close() {
        // Signal first so bounded polling leaves the I/O gate before releasing the integer.
        if (closed.compareAndSet(false, true)) synchronized(ioGate) { sockets.close(socket) }
    }

    private fun writeFrame(
        frame: PortalFrame,
        deadline: Deadline,
    ) {
        io.write(frame.headerBytes(), deadline)
        io.write(frame.payload.copyToByteArray(), deadline)
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // All partial transfers invalidate framing; cleanup must preserve fatal precedence.
    private fun <T> terminalOnFailure(action: () -> T): T = synchronized(ioGate) {
        check(!closed.get()) { "portal channel is closed" }
        try {
            action()
        } catch (idle: PortalReadTimeoutException) {
            throw idle
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup is Error && failure !is Error) {
                    cleanup.addSuppressed(failure)
                    throw cleanup
                }
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }
}

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
                    val granted = FileDescriptor.adopt(opened.value.toInt(), FileDescriptorRole.Granted)
                    return Capability.ReadFd(granted)
                }
            }
        } finally {
            LinuxNative.fileSystem.close(rootFd)
        }
    }
}
