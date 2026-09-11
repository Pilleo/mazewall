package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.seccomp.SeccompNotification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Host-safe native contracts use only this process and owned temporary files; no filter is installed. */
class TraceeAuthorityNativeContractTest {
    @TempDir lateinit var root: Path

    @Test
    fun `captured open honors authorization flags and descriptor ownership`() {
        val target = Files.writeString(root.resolve("target"), "captured contents")
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            val notification = notification(Arch.current().openat, longArrayOf(-100, arena.allocateFrom(target.toString()).address(), NativeConstants.O_CLOEXEC.toLong(), 0, 0, 0))
            TraceeProcAuthority.capture(notification.pid, LinuxNative).use { authority ->
                val execution = TraceeAuthorityExecution(LinuxNative, authority, notification, emptyList())
                val snapshot = execution.prepare()
                assertEquals(SupervisorRoute.AskJvm, snapshot.route)
                assertEquals(target.toRealPath().toString(), snapshot.resolvedPath)
                assertEquals(SupervisorReplyEffect.Error(13), execution.execute(TraceeAuthorityDecision(SupervisorRoute.Abort(13, "policy"), null)))
                val opened = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, execution.execute(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null)))
                val descriptor = opened.descriptor.handle.value
                opened.descriptor.use {
                    assertEquals("captured contents", Files.readString(Path.of("/proc/self/fd/$descriptor")))
                    assertEquals(NativeConstants.O_CLOEXEC, opened.flags.value)
                }
                assertFalse(Files.exists(Path.of("/proc/self/fd/$descriptor")))
            }
        }
        }
    }

    @Test
    fun `bypass is resolved structurally and missing target retains ENOENT`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            val target = root.resolve("missing.class")
            val notification = notification(Arch.current().openat, longArrayOf(-100, arena.allocateFrom(target.toString()).address(), 0, 0, 0, 0))
            TraceeProcAuthority.capture(notification.pid, LinuxNative).use { authority ->
                val execution = TraceeAuthorityExecution(LinuxNative, authority, notification, listOf(root.toString()))
                assertEquals(SupervisorRoute.InjectFd, execution.prepare().route)
                assertEquals(SupervisorReplyEffect.Error(2), execution.execute(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null)))
            }
        }
        }
    }

    @Test
    fun `exec captures executable descriptor and immutable nul without executing it`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            val notification = notification(Arch.current().execve, longArrayOf(arena.allocateFrom("/bin/true").address(), 12, 13, 0, 0, 0))
            TraceeProcAuthority.capture(notification.pid, LinuxNative).use { authority ->
                val execution = TraceeAuthorityExecution(LinuxNative, authority, notification, emptyList())
                assertEquals(SupervisorRoute.AskJvm, execution.prepare().route)
                val result = assertInstanceOf(SupervisorReplyEffect.SecureExecAndReply::class.java, execution.execute(TraceeAuthorityDecision(SupervisorRoute.SecureExec, null)))
                SupervisorReplyHandoff(result).use {
                    val opened = assertInstanceOf(SupervisorDescriptorResult.Opened::class.java, result.target)
                    assertEquals(Path.of("/bin/true").toRealPath(), Path.of("/proc/self/fd/${opened.descriptor.handle.value}").toRealPath())
                    assertTrue(checkNotNull(result.capturedEmptyPath) > 0)
                    assertEquals(0.toByte(), authority.read(checkNotNull(result.capturedEmptyPath), 1).single())
                    assertEquals(12L, result.request.argv)
                    assertEquals(13L, result.request.envp)
                }
            }
        }
        }
    }

    @Test
    fun `scalar spawn alone accepts continue while unknown and pointer routes deny it`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            TraceeProcAuthority.capture(LinuxNative.process.gettid().value, LinuxNative).use { authority ->
                for (nr in listOf(Arch.current().clone, Int.MAX_VALUE, Arch.current().accept4)) {
                    val execution = TraceeAuthorityExecution(LinuxNative, authority, notification(nr, LongArray(6)), emptyList())
                    val prepared = execution.prepare()
                    if (nr == Int.MAX_VALUE) {
                        assertInstanceOf(SupervisorRoute.Abort::class.java, prepared.route)
                    } else {
                        assertEquals(SupervisorRoute.AskJvm, prepared.route)
                    }
                    assertEquals(
                        if (nr == Arch.current().clone) SupervisorReplyEffect.Continue else SupervisorReplyEffect.Error(),
                        execution.execute(TraceeAuthorityDecision(SupervisorRoute.Continue, null)),
                    )
                }
            }
        }
        }
    }

    @Test
    fun `unsupported accept output and invalid path fail before descriptor import`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
            TraceeProcAuthority.capture(LinuxNative.process.gettid().value, LinuxNative).use { authority ->
                val accept = TraceeAuthorityExecution(LinuxNative, authority, notification(Arch.current().accept4, longArrayOf(-1, 1, 0, 0, 0, 0)), emptyList())
                accept.prepare()
                assertEquals(SupervisorReplyEffect.UnsupportedAuthority(UnsupportedTraceeAuthority.AcceptPeerMemory), accept.execute(TraceeAuthorityDecision(SupervisorRoute.InjectFd, null)))
                val open = TraceeAuthorityExecution(LinuxNative, authority, notification(Arch.current().openat, longArrayOf(-100, 0, 0, 0, 0, 0)), emptyList())
                assertInstanceOf(SupervisorRoute.Abort::class.java, open.prepare().route)
                assertEquals(14, assertThrows(TraceeAuthorityFailure::class.java) { authority.readPath(1) }.errno)
                assertEquals(9, assertThrows(TraceeAuthorityFailure::class.java) { authority.directory(TraceeDirFd(Int.MIN_VALUE)) }.errno)
                assertSame(authority.directory(TraceeDirFd.CurrentWorkingDirectory), authority.directory(TraceeDirFd.CurrentWorkingDirectory))
            }
        }
        }
    }

    private fun notification(
        nr: Int,
        args: LongArray,
    ) = SeccompNotification(42, LinuxNative.process.gettid().value, Arch.current().audit, nr, args)
}
