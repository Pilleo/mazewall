package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.seccomp.daemon.LoopAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class SupervisorSessionMatrixTest {
    private data class Case(
        val name: String,
        val nr: Int = Arch.AMD64.clone,
        val configure: (SupervisorBoundaryFixture) -> Unit = {},
        val expectedReplies: Int = 1,
        val expectedError: Int = 0,
        val expectedInjection: Boolean = false,
        val expectedHandled: Boolean = true,
        val invalidState: SupervisorSessionState? = null,
    )

    @TestFactory
    fun `every terminal route has one confirmed reply or an explicit terminal failure`(): List<DynamicTest> =
        listOf(
            Case("continue"),
            Case("policy deny", configure = { it.decision = 0 }, expectedError = -13),
            Case("timeout", configure = { it.engine.pollResult = SupervisorBoundaryFixture.success(0) }, expectedError = -1, expectedHandled = false),
            Case("malformed verdict", configure = { it.decision = 99 }, expectedError = -1),
            Case("oversized path frame", Arch.AMD64.open, { it.pathLength = 4097 }, expectedError = -1),
            Case("open", Arch.AMD64.open, expectedError = -1),
            Case("openat", Arch.AMD64.openat, expectedError = -1),
            Case("openat2", Arch.AMD64.openat2, expectedError = -1),
            Case("connect", Arch.AMD64.connect),
            Case("accept", Arch.AMD64.accept, expectedInjection = true),
            Case("secure exec without path", Arch.AMD64.execve, expectedError = -1),
            Case("async failure", Arch.AMD64.accept, { it.engine.networking.acceptResult = SupervisorBoundaryFixture.failure(13) }, expectedError = -13),
            Case("id mismatch", configure = { it.responseId = 99 }, expectedError = -1, expectedHandled = false),
            Case("reply failure", configure = { it.replyResult = SupervisorBoundaryFixture.failure(13) }, expectedHandled = false),
            Case("injection failure", Arch.AMD64.open, { it.injectionResult = SupervisorBoundaryFixture.failure(1) }, expectedError = -1),
            Case("async injection failure", Arch.AMD64.accept, { it.injectionResult = SupervisorBoundaryFixture.failure(13) }, expectedInjection = true, expectedHandled = false),
            Case("async denial send failure", Arch.AMD64.accept, {
                it.engine.networking.acceptResult = SupervisorBoundaryFixture.failure(13)
                it.replyResult = SupervisorBoundaryFixture.failure(13)
            }, expectedError = -13, expectedHandled = false),
            Case("async post-reply cleanup failure", Arch.AMD64.accept, { fixture ->
                fixture.engine.fileSystem.onClose = { fd ->
                    fixture.closed += fd.value
                    if (fd.value == 51004) SupervisorBoundaryFixture.failure(5) else SupervisorBoundaryFixture.success(0)
                }
            }, expectedInjection = true, expectedHandled = false),
            Case("protocol violation", expectedReplies = 0, expectedHandled = false, invalidState = SupervisorSessionState.ResolvingPath),
        ).map { case ->
            DynamicTest.dynamicTest(case.name) {
                val fixture = SupervisorBoundaryFixture()
                case.configure(fixture)
                NativeArena.ofConfined().use { arena ->
                    val handler = fixture.handler()
                    val handled = if (case.invalidState == null) {
                        handler.processNotification(fixture.notification(arena, case.nr), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                    } else {
                        with(arena) { handler.readAndHandleJvmResponse(fixture.context(arena, case.nr), case.invalidState) }
                    }
                    if (case.expectedReplies != 0) fixture.awaitTerminalProcessing()
                    // Pending acceptance is not a terminal outcome; the worker barrier above is.
                    if (case.nr != Arch.AMD64.accept) assertEquals(case.expectedHandled, handled, "synchronous terminal result")
                    val finalAction = with(arena) {
                        handler.handleActiveListener(arena.allocate(Layouts.POLLFD, 2), arena.allocate(Layouts.SECCOMP_NOTIF), arena.allocate(Layouts.SECCOMP_NOTIF_RESP))
                    }
                    assertEquals(if (case.expectedHandled) LoopAction.Continue else LoopAction.Break, finalAction, "post-cleanup session outcome")
                    assertEquals(case.expectedReplies, fixture.replies.size, "terminal failure must never attempt a second reply")
                    fixture.replies.forEach { reply ->
                        assertEquals(42L, reply.id)
                        assertEquals(case.expectedError, reply.error)
                        assertEquals(case.expectedInjection, reply.injected)
                    }
                }
            }
        }
}
