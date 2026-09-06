package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ManagedSegment

/** Immutable notification fields shared by every supervisor route. */
internal data class NotifHeader(
    val nr: Int,
    val tid: Tid,
    val arch: Arch,
    val audit: Int,
    val ppid: Int,
    val args: LongArray,
)

/** Request frame sent to the JVM policy listener. */
internal data class JvmVerdictRequest(
    val id: Long,
    val header: NotifHeader,
    val path: String?,
    val sockaddrBytes: ByteArray?,
)

/** Data required to execute one route and emit its single seccomp response. */
internal data class SupervisorRouteContext(
    val request: JvmVerdictRequest,
    val extracted: SyscallArguments,
    val response: ManagedSegment,
)
