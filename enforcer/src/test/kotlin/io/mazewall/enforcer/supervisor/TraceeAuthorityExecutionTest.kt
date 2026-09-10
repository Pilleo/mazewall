package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.NativeFileSystem
import io.mazewall.core.Arch
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.OpenFlags
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readString
import io.mazewall.platform.seccomp.SeccompNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TraceeAuthorityExecutionTest {
    @Test
    fun `unreadable child exec still validates parent context and retains kernel denial`() {
        val engine = object : NativeEngine by LinuxNative {
            override val fileSystem = object : NativeFileSystem by LinuxNative.fileSystem {
                override fun openat(
                    dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    path: ManagedSegment,
                    flags: OpenFlags,
                ) = openat(dirfd, path, flags, 0)

                override fun openat(
                    dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    path: ManagedSegment,
                    flags: OpenFlags,
                    mode: Int,
                ): NativeResult =
                    if (path.readString(0) == "mem") {
                        LinuxNative.SyscallResult.Error(NativeConstants.EACCES, -1L)
                    } else {
                        LinuxNative.fileSystem.openat(dirfd, path, flags, mode)
                    }
            }
        }
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val notification = SeccompNotification(
                    42,
                    LinuxNative.process.gettid().value,
                    Arch.current().audit,
                    Arch.current().execve,
                    longArrayOf(arena.allocateFrom("/bin/true").address(), 0, 0, 0, 0, 0),
                )
                TraceeProcAuthority.capture(notification.pid, engine).use { authority ->
                    val execution = TraceeAuthorityExecution(engine, authority, notification, emptyList())
                    val snapshot = execution.prepare()
                    assertEquals(SupervisorRoute.AskJvm, snapshot.route)
                    assertEquals(
                        ProcessHandle
                        .current()
                        .parent()
                        .orElseThrow()
                        .pid()
                        .toInt(),
                            snapshot.ppid,
                    )
                    assertNull(snapshot.path)
                    val denied = assertInstanceOf(
                        SupervisorReplyEffect.SecureExecAndReply::class.java,
                        execution.execute(TraceeAuthorityDecision(SupervisorRoute.SecureExec, "/bin/true")),
                    )
                    assertEquals(SupervisorDescriptorResult.Denied(NativeConstants.EACCES), denied.target)
                    assertEquals(0L, denied.capturedEmptyPath)
                }
            }
        }
    }
}
