package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.IoctlCommand
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Deadline
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.daemon.LoopAction
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.seccomp.daemon.NotificationWork
import io.mazewall.platform.seccomp.daemon.SeccompSessionHandler
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SupervisorAdmissionTest {
    @Test
    fun `global limit is exactly 128 independently of per TID capacity`() {
        val admission = SupervisorAdmission()
        val leases = (1..128).map { checkNotNull(admission.acquire(Tid(it))) }
        assertNull(admission.acquire(Tid(129)))
        leases.first().close()
        leases.first().close()
        checkNotNull(admission.acquire(Tid(129))).use {
            assertNull(admission.acquire(Tid(130)), "double close must not manufacture capacity")
        }
        leases.forEach { it.close() }
    }

    @Test
    fun `TID accounting spans distinct listener handlers and teardown releases once`() {
        val fixture = SupervisorBoundaryFixture()
        val admission = SupervisorAdmission()
        val first = fixture.handler(admission = admission)
        val second = fixture.handler(admission = admission)
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val notification = fixture.notification(arena, 999999)
                val pending = (0 until 8).map { index ->
                    (if (index < 4) first else second).beginNotification(notification, arena.allocate(Layouts.SECCOMP_NOTIF_RESP), fixture.listener, fixture.socket, Deadline.afterMillis(30000))
                }
                second.beginNotification(notification, arena.allocate(Layouts.SECCOMP_NOTIF_RESP), fixture.listener, fixture.socket, Deadline.afterMillis(30000)).use {
                    (it.poll() as NotificationProgress.Ready).reply()
                }
                assertEquals(listOf(-11), fixture.replies.map { it.error })
                first.close()
                first.close()
                val replacements = (0 until 4).map { checkNotNull(admission.acquire(Tid(1))) }
                assertNull(admission.acquire(Tid(1)))
                second.close()
                pending.forEach { it.close() }
                replacements.forEach { it.close() }
                val all = (0 until 8).map { checkNotNull(admission.acquire(Tid(1))) }
                assertNull(admission.acquire(Tid(1)))
                all.forEach { it.close() }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [4, 5, 2])
    fun `overload sends one EAGAIN through reactor even on EINTR or failure`(errno: Int) {
        val fixture = SupervisorBoundaryFixture()
        val admission = SupervisorAdmission()
        val leases = (0 until 8).map { checkNotNull(admission.acquire(Tid(1))) }
        fixture.replyResult = SupervisorBoundaryFixture.failure(errno)
        val io = fixture.engine.onIoctl
        fixture.engine.onIoctl = { fd, command, buffer ->
            if (command == IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV.code) {
                notification(buffer)
                SupervisorBoundaryFixture.success(0)
            } else {
                io(fd, command, buffer)
            }
        }
        fixture.handler(admission = admission).use { handler ->
            SeccompSessionHandler(fixture.socket, fixture.listener, handler, engine = fixture.engine, socketManager = fixture.sockets).use { reactor ->
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                        val poll = arena.allocate(Layouts.POLLFD, 2)
                        PollFdSegment.of(poll).setRevents(NativeConstants.POLLIN)
                        assertEquals(LoopAction.Break, reactor.handleActiveListener(poll))
                        assertEquals(LoopAction.Break, reactor.handleActiveListener(poll))
                        reactor.close()
                        assertEquals(listOf(-11), fixture.replies.map { it.error })
                        assertEquals(0, fixture.readCount)
                    }
                }
            }
        }
        leases.forEach { it.close() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["deny", "success", "timeout", "exception", "shutdown"])
    fun `reactor releases counted work on every terminal path`(path: String) {
        val fixture = SupervisorBoundaryFixture()
        val admission = SupervisorAdmission()
        val leases = (0 until 7).map { checkNotNull(admission.acquire(Tid(1))) }
        val io = fixture.engine.onIoctl
        fixture.engine.onIoctl = { fd, command, buffer ->
            if (command == IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV.code) {
                notification(buffer, if (path == "success") Arch.AMD64.clone else 999999)
                if (path == "exception") buffer.writeInt(20, 0)
                SupervisorBoundaryFixture.success(0)
            } else {
                io(fd, command, buffer)
            }
        }
        var now = System.nanoTime()
        fixture.handler(admission = admission).use { handler ->
            SeccompSessionHandler(fixture.socket, fixture.listener, handler, engine = fixture.engine, socketManager = fixture.sockets, nanoTime = { now }).use { reactor ->
                NativeArena.ofConfined().use { arena ->
                    with(arena) {
                        val poll = arena.allocate(Layouts.POLLFD, 2)
                        PollFdSegment.of(poll).setRevents(NativeConstants.POLLIN)
                        if (path == "exception") {
                            assertThrows(UnsupportedOperationException::class.java) { reactor.handleActiveListener(poll) }
                        } else {
                            reactor.handleActiveListener(poll)
                            assertNull(admission.acquire(Tid(1)))
                            PollFdSegment.of(poll).setRevents(0)
                            when (path) {
                                "timeout" -> {
                                    now += 30_000_000_000L
                                    reactor.handleActiveListener(poll)
                                }
                                "shutdown" -> reactor.close()
                                else -> repeat(3) { reactor.handleActiveListener(poll) }
                            }
                        }
                        // Closing the reactor retires any still-pending work before capacity is
                        // borrowed for the teardown assertion.
                        reactor.close()
                        checkNotNull(admission.acquire(Tid(1))).use {
                            assertNull(admission.acquire(Tid(1)))
                            reactor.close()
                            assertNull(admission.acquire(Tid(1)), "repeated teardown must not release twice")
                        }
                        assertEquals(1, fixture.replies.size)
                    }
                }
            }
        }
        leases.forEach { it.close() }
    }

    private fun notification(
        buffer: ManagedSegment,
        nr: Int = 999999,
    ) {
        buffer.writeLong(0, 42)
        buffer.writeInt(8, 1)
        buffer.writeInt(16, nr)
        buffer.writeInt(20, Arch.AMD64.audit)
    }

    @Test
    fun `ninth pending notification from one TID is rejected before preparation`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.handler().use { handler ->
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val notification = fixture.notification(arena, 999999)
                    val pending = (0 until 9).map {
                        handler.beginNotification(notification, arena.allocate(Layouts.SECCOMP_NOTIF_RESP), fixture.listener, fixture.socket, Deadline.afterMillis(30000))
                    }
                    try {
                        var progress = pending.last().poll()
                        if (progress == NotificationProgress.Await) progress = pending.last().poll()
                        (progress as NotificationProgress.Ready).reply()
                        assertEquals(listOf(-11), fixture.replies.map { it.error })
                        assertEquals(0, fixture.readCount)
                    } finally {
                        pending.forEach { it.close() }
                    }
                }
            }
        }
    }
}
