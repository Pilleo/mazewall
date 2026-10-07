package io.mazewall.portal

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.socket.NativeCloseFailure
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Thrown when a portal read deadline expires. Distinct from fatal socket errors so idle
 *  workers can keep polling (issue: pooled-worker 30s self-exit). */
internal class PortalReadTimeoutException : java.io.IOException("portal read timed out")

internal class PortalIncompleteFrameException : java.io.IOException("portal frame incomplete at deadline")

/** Clean peer shutdown before receiving the first byte of a new frame. */
internal class PortalPeerClosedException : java.io.EOFException("portal peer closed between frames")

/**
 * Owns the supplied socket and descriptors received but not yet delivered to a caller.
 * Successful receive transfers capabilities to its caller; sending borrows capabilities only.
 * Close rejects new I/O, retires all retained owners, and permits retry after pre-native failure.
 */
internal class PortalChannel(
    private val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    private val sockets: SocketManager = RealSocketManager,
    private val sendPayloadLimit: Int = PortalFrame.MAX_PAYLOAD,
    private val receivePayloadLimit: Int = PortalFrame.MAX_PAYLOAD,
    private val capabilityLimit: Int = PortalFrame.MAX_FDS,
) : AutoCloseable {
    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_CONTROL_BYTES = 512
    }

    private val closed = java.util.concurrent.atomic
        .AtomicBoolean()
    private val ioGate = ReentrantLock()
    private var socketRetired = false
    private val received = ArrayList<Capability.ReadFd>(PortalFrame.MAX_FDS)
    private val io = PortalIo(socket) { closed.get() }

    init {
        require(sendPayloadLimit in 1..PortalFrame.MAX_PAYLOAD && receivePayloadLimit in 1..PortalFrame.MAX_PAYLOAD)
        require(capabilityLimit in 0..PortalFrame.MAX_FDS)
    }

    /**
     * Sends a frame without `SCM_RIGHTS`. Worker replies use this path.
     * Capability grants go through [sendCapabilities] (broker → worker only).
     */
    fun send(
        frame: PortalFrame,
        deadline: Deadline = Deadline.afterMillis(DEFAULT_TIMEOUT_MS),
    ) {
        require(frame.fdCount == 0) {
            "portal frames with file descriptors must use sendCapabilities (broker → worker only)"
        }
        require(frame.payload.size <= sendPayloadLimit) { "Portal frame exceeds configured send payload bound" }
        terminalOnFailure(deadline) {
            writeFrame(frame, deadline)
        }
    }

    internal fun sendCapabilities(
        frame: PortalFrame,
        capabilities: List<Capability.ReadFd>,
        deadline: Deadline,
    ) {
        require(capabilities.size == frame.fdCount)
        require(frame.payload.size <= sendPayloadLimit && frame.fdCount <= capabilityLimit) { "Portal frame exceeds configured send bounds" }
        terminalOnFailure(deadline) {
            writeFrame(frame, deadline)
            capabilities.forEach { capability -> capability.borrowUntil(deadline, closed::get) { io.sendDescriptor(it, deadline) } }
        }
    }

    fun receive(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Pair<PortalFrame, List<Capability.ReadFd>> = receive(Deadline.afterMillis(timeoutMs))

    fun receive(deadline: Deadline): Pair<PortalFrame, List<Capability.ReadFd>> {
        return receiveBounded(deadline, receivePayloadLimit, capabilityLimit)
    }

    internal fun sendControl(
        frame: PortalFrame,
        deadline: Deadline,
    ) {
        require(frame.method == PortalParentAuthorization.METHOD && frame.requestId == 0 && frame.fdCount == 0 && frame.payload.size <= MAX_CONTROL_BYTES) { "invalid startup control frame" }
        terminalOnFailure(deadline) { writeFrame(frame, deadline) }
    }

    internal fun receiveControl(deadline: Deadline): PortalFrame = receiveBounded(deadline, MAX_CONTROL_BYTES, 0).first

    private fun receiveBounded(
        deadline: Deadline,
        payloadLimit: Int,
        fdLimit: Int,
    ): Pair<PortalFrame, List<Capability.ReadFd>> {
        return terminalOnFailure(deadline) {
            val header = PortalFrame.parseHeader(io.read(PortalFrame.HEADER_SIZE, deadline, idleAllowed = true))
            require(header.payloadLen <= payloadLimit && header.fdCount <= fdLimit) { "Portal response exceeds configured transport bounds" }
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
        return CleanupFailures.merge(first, next)
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
                    return Capability.ReadFd(granted).requireRegularFile(relative)
                }
            }
        } finally {
            LinuxNative.fileSystem.close(rootFd)
        }
    }
}

/**
 * `openat2` pins the resolved object before this check. Looking it up through the owned
 * descriptor therefore validates that exact object instead of re-resolving the caller path.
 */
@Suppress("TooGenericExceptionCaught") // Validation must close the exact acquired owner for every failure kind.
private fun Capability.ReadFd.requireRegularFile(relative: String): Capability.ReadFd =
    try {
        val isRegular = borrow { fd -> Files.isRegularFile(Path.of("/proc/self/fd/${fd.value}")) }
        require(isRegular) { "granted path is not a regular file: $relative" }
        this
    } catch (failure: Throwable) {
        try {
            close()
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
