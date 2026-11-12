package io.mazewall.ffi.networking

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.writeInt
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout

/** Linux SO_PEERCRED is three 32-bit ints, not JVM longs. Verify the actual process launched by this owner. */
internal object SupervisorHelperCredentials {
    data class PeerCredentials(
        val pid: Pid,
        val uid: Long,
        val gid: Long,
    )

    fun observe(
        engine: NativeEngine,
        socket: FileDescriptor<*, FdState.Open, FdOwnership>,
    ): PeerCredentials = readPeer(engine, socket).also { check(it.pid.value > 0) { "Invalid supervisor helper peer PID" } }

    private const val CREDENTIAL_BYTES = 3 * Int.SIZE_BYTES
    private const val UID_OFFSET = 4L
    private const val GID_OFFSET = 8L
    private val CREDENTIAL = MemoryLayout.structLayout(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    private const val SOL_SOCKET = 1
    private const val SO_PEERCRED = 17

    fun verify(
        engine: NativeEngine,
        socket: FileDescriptor<*, FdState.Open, FdOwnership>,
        expectedPid: Long,
    ) {
        check(readPeer(engine, socket).pid.value.toLong() == expectedPid) { "Supervisor helper peer identity mismatch" }
    }

    private fun readPeer(
        engine: NativeEngine,
        socket: FileDescriptor<*, FdState.Open, FdOwnership>,
    ): PeerCredentials =
        NativeArena.ofConfined().use { arena ->
            val credential = arena.allocate(CREDENTIAL)
            val length = arena.allocate(ValueLayout.JAVA_INT).also { it.writeInt(0, CREDENTIAL_BYTES) }
            engine.raw
                .syscall(
                    Arch.current().getsockopt.toLong(),
                    NativeArg.FdArg(socket),
                    NativeArg.IntArg(SOL_SOCKET),
                    NativeArg.IntArg(SO_PEERCRED),
                    NativeArg.MemoryArg(credential),
                    NativeArg.MemoryArg(length),
                ).helperValue("authenticate supervisor helper")
            check(length.readInt(0) == CREDENTIAL_BYTES) { "Supervisor helper peer identity mismatch" }
            PeerCredentials(Pid(credential.readInt(0)), credential.readInt(UID_OFFSET).toUInt().toLong(), credential.readInt(GID_OFFSET).toUInt().toLong())
        }
}
