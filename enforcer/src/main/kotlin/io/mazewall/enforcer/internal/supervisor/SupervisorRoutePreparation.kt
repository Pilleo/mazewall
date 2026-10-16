package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.SupervisorProcessMemoryReader
import io.mazewall.ffi.memory.TraceeMemoryReader
import io.mazewall.platform.lifecycle.SyscallNumber
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.syscall.Arch

internal data class PreparedSupervisorRoute(
    val context: SupervisorRouteContext,
    val route: SupervisorRoute,
)

/** Bounded argument inspection and path resolution never grant JVM spawn/exec authority. */
internal class SupervisorRoutePreparation(
    private val memory: TraceeMemoryReader = SupervisorProcessMemoryReader,
    private val parentPid: (Int) -> Int = ProcFsInspector::getPpid,
) {
    context(arena: NativeArena) fun prepare(
        notification: SeccompNotification,
        response: ManagedSegment,
    ): PreparedSupervisorRoute {
        val arch = Arch.fromAudit(notification.arch)
        val tid = Tid(notification.pid)
        val args = SupervisorArgumentExtractor.extract(
            notification.nr,
            tid,
            notification.args,
            arch,
            readString = { thread, address -> readPath(thread, address) },
            readBytes = { thread, address, length -> memory.readBytes(thread, address, length) },
        )
        val header = NotifHeader(notification.nr, tid, arch, notification.arch, parentPid(notification.pid), notification.args)
        val kind = SupervisorNotificationMachine.classify(SyscallNumber(header.nr), arch)
        val resolved = SupervisorFastPathResolution.resolve(notification.pid, kind, args)
        val request = JvmVerdictRequest(notification.id, header, args.pathStr, args.sockaddrBytes)
        return PreparedSupervisorRoute(
            SupervisorRouteContext(request, args, response),
            SupervisorNotificationMachine.evaluateFastPath(kind, resolved.path, args.pathStr),
        )
    }

    context(arena: NativeArena) private fun readPath(
        tid: Tid,
        address: Long,
    ): String? =
        try {
            memory.readString(tid, address, MAX_PATH_LENGTH)
        } catch (_: ContainmentViolationException) {
            null
        }

    private companion object {
        const val MAX_PATH_LENGTH = 4096
    }
}
