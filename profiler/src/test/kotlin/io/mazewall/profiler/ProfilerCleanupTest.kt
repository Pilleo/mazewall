package io.mazewall.profiler

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.NativeEngine
import io.mazewall.NativeFileSystem
import io.mazewall.PolicyPresets
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Tid
import io.mazewall.ffi.networking.SupervisorSocketUtils
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.profiler.engine.RealProfilerInstaller
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.ProfilerTraceListener
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private typealias OwnedDescriptor = FileDescriptor<*, FdState.Open, FdOwnership.Owned>
private typealias CloseResult = LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>

@EnabledIfLinuxAndSupported
class ProfilerCleanupTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ordinary cleanup failure retains only retryable listener ownership`(cancel: Boolean) {
        CleanupFixture().use { fixture ->
            val session = fixture.service.openSession()
            val first = fixture.listener(session)
            val second = fixture.listener(session)
            val failure = if (cancel) CancellationException("before dispatch") else IllegalStateException("before dispatch")
            fixture.closeOperation = { fd ->
                if (fixture.attempts.size == 1) throw failure
                fixture.closeNative(fd)
            }

            val thrown = assertFailsWith<Exception> { fixture.service.shutdown() }
            if (cancel) assertSame(failure, thrown) else assertSame(failure, thrown.suppressed.single())
            assertEquals(listOf(first.value, second.value), fixture.attempts)
            val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(session.cleanupOutcome)
            assertEquals(listOf(failure), outcome.failures)
            assertEquals(listOf(first), outcome.retryableOwnership)
            assertEquals(outcome, fixture.service.cleanupOutcome)
            assertTrue(session in fixture.sessions())

            fixture.service.shutdown()
            fixture.service.shutdown()
            assertEquals(listOf(first.value, second.value, first.value), fixture.attempts)
            assertEquals(SeccompCleanupOutcome.Complete, fixture.service.cleanupOutcome)
            assertTrue(fixture.sessions().isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fatal cleanup retains the session and reaches unattempted listeners on retry`(retired: Boolean) {
        CleanupFixture().use { fixture ->
            val session = fixture.service.openSession()
            val first = fixture.listener(session)
            val second = fixture.listener(session)
            val fatal = LinkageError("native close boundary")
            session.state.register(Tid(73), Thread.currentThread())
            fixture.closeOperation = { fd ->
                if (fixture.attempts.size == 1) {
                    if (retired) fixture.closeNative(fd)
                    throw fatal
                }
                fixture.closeNative(fd)
            }

            assertSame(fatal, assertFailsWith<LinkageError> { fixture.service.shutdown() })
            assertEquals(listOf(first.value), fixture.attempts)
            val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(session.cleanupOutcome)
            assertSame(fatal, outcome.failures.single().cause)
            assertEquals(if (retired) listOf(second) else listOf(first, second), outcome.retryableOwnership)
            assertEquals(outcome, fixture.service.cleanupOutcome)
            assertTrue(session in fixture.sessions())
            assertSame(Thread.currentThread(), session.state.threadFor(Tid(73)))

            if (retired) {
                assertFailsWith<IllegalStateException> { fixture.service.shutdown() }
                assertFailsWith<IllegalStateException> { fixture.service.shutdown() }
                assertEquals(listOf(first.value, second.value), fixture.attempts)
                val terminal = assertIs<SeccompCleanupOutcome.Incomplete>(fixture.service.cleanupOutcome)
                assertSame(fatal, terminal.failures.single().cause)
                assertTrue(terminal.retryableOwnership.isEmpty())
                assertTrue(session in fixture.sessions())
            } else {
                fixture.service.shutdown()
                fixture.service.shutdown()
                assertEquals(listOf(first.value, first.value, second.value), fixture.attempts)
                assertEquals(SeccompCleanupOutcome.Complete, fixture.service.cleanupOutcome)
                assertTrue(fixture.sessions().isEmpty())
            }
            assertNull(session.state.threadFor(Tid(73)))
            assertFalse(first.isLiveForIo())
            assertFalse(second.isLiveForIo())
        }
    }

    @Test
    fun `fatal service fold includes ownership of sessions it did not reach`() {
        CleanupFixture().use { fixture ->
            val first = fixture.listener(fixture.service.openSession())
            val second = fixture.listener(fixture.service.openSession())
            val fatal = LinkageError("first session cannot dispatch close")
            fixture.closeOperation = { fd ->
                if (fixture.attempts.size == 1) throw fatal
                fixture.closeNative(fd)
            }

            assertSame(fatal, assertFailsWith<LinkageError> { fixture.service.shutdown() })
            val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(fixture.service.cleanupOutcome)
            assertSame(fatal, outcome.failures.single().cause)
            assertEquals(setOf(first, second), outcome.retryableOwnership.toSet())
            assertEquals(2, fixture.sessions().size)

            fixture.service.shutdown()
            assertEquals(3, fixture.attempts.size)
            assertEquals(setOf(first.value, second.value), fixture.attempts.toSet())
            assertEquals(SeccompCleanupOutcome.Complete, fixture.service.cleanupOutcome)
            assertTrue(fixture.sessions().isEmpty())
        }
    }

    @Test
    fun `returned native close error remains observable after siblings close without retrying the consumed token`() {
        CleanupFixture().use { fixture ->
            val session = fixture.service.openSession()
            val first = fixture.listener(session)
            val second = fixture.listener(session)
            fixture.closeOperation = { fd ->
                fixture.closeNative(fd)
                if (fd == first) LinuxNative.SyscallResult.Error(13, -1L) else LinuxNative.SyscallResult.Success(0L)
            }

            repeat(2) { assertFailsWith<IllegalStateException> { fixture.service.shutdown() } }
            assertEquals(listOf(first.value, second.value), fixture.attempts)
            val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(fixture.service.cleanupOutcome)
            assertTrue(
                outcome.failures
                .single()
                .message!!
                .contains("errno=13"),
            )
            assertTrue(outcome.retryableOwnership.isEmpty())
            assertEquals(outcome, session.cleanupOutcome)
            assertTrue(session in fixture.sessions())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `session callback runs only after all listener cleanup completes`(fatal: Boolean) {
        CleanupFixture().use { fixture ->
            var notifications = 0
            val session = fixture.session { notifications++ }
            fixture.listener(session)
            fixture.listener(session)
            val failure = if (fatal) LinkageError("before dispatch") else IllegalStateException("before dispatch")
            fixture.closeOperation = { fd ->
                if (fixture.attempts.size == 1) throw failure
                fixture.closeNative(fd)
            }

            if (fatal) {
                assertSame(failure, assertFailsWith<LinkageError> { session.closeWithResult() })
            } else {
                assertIs<SeccompCleanupOutcome.Incomplete>(session.closeWithResult())
            }
            assertEquals(0, notifications, "An incomplete fold must not publish session closure")
            assertEquals(SeccompCleanupOutcome.Complete, session.closeWithResult())
            assertEquals(1, notifications)
            assertEquals(SeccompCleanupOutcome.Complete, session.closeWithResult())
            assertEquals(1, notifications, "The completed callback must not be dispatched again")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `callback failure is recorded before propagation and retried without closing listeners again`(fatal: Boolean) {
        CleanupFixture().use { fixture ->
            var notifications = 0
            val failure = if (fatal) LinkageError("onClosed failed") else IllegalStateException("onClosed failed")
            val session = fixture.session { closed ->
                assertEquals(SeccompCleanupOutcome.Complete, closed.cleanupOutcome)
                assertEquals(2, fixture.attempts.size)
                if (++notifications == 1) throw failure
            }
            val first = fixture.listener(session)
            val second = fixture.listener(session)

            if (fatal) {
                assertSame(failure, assertFailsWith<LinkageError> { session.closeWithResult() })
            } else {
                assertIs<SeccompCleanupOutcome.Incomplete>(session.closeWithResult())
            }
            val outcome = assertIs<SeccompCleanupOutcome.Incomplete>(session.cleanupOutcome)
            if (fatal) assertSame(failure, outcome.failures.single().cause) else assertSame(failure, outcome.failures.single())
            assertTrue(outcome.retryableOwnership.isEmpty())

            assertEquals(SeccompCleanupOutcome.Complete, session.closeWithResult())
            assertEquals(SeccompCleanupOutcome.Complete, session.closeWithResult())
            assertEquals(2, notifications)
            assertEquals(listOf(first.value, second.value), fixture.attempts)
        }
    }

    /** Acquires real sockets; injects failure around their captured native close without installing filters. */
    private class CleanupFixture : AutoCloseable {
        private val actual = LinuxNative.snapshot()
        private val acquired = mutableListOf<OwnedDescriptor>()
        val attempts = mutableListOf<Int>()
        var closeOperation: (OwnedDescriptor) -> CloseResult = ::closeNative
        private val native = object : NativeEngine by MockNativeEngine() {
            override val fileSystem = object : NativeFileSystem by actual.fileSystem {
                override fun close(fd: OwnedDescriptor): CloseResult {
                    attempts += fd.value
                    return closeOperation(fd)
                }
            }
        }
        private val manager = ProfilerDaemonManager(MockNativeEngine(), ProfilerTest.MockSocketManager(), ProfilerTest.MockProcessLauncher())
        private val runtime = ProfilerRuntime({ manager }, RealProfilerInstaller(native, false), { Tid(73) }, native)
        val service = ProfilerService(runtime)

        fun session(onClosed: (ProfilerSession) -> Unit): ProfilerSession = ProfilerSession(runtime, manager.getOrSpawnSharedDaemon(), PolicyPresets.PURE_COMPUTE_UNSAFE, false, false, onClosed)

        fun closeNative(fd: OwnedDescriptor): CloseResult = actual.fileSystem.close(fd)

        @Suppress("UNCHECKED_CAST")
        fun listener(session: ProfilerSession): OwnedDescriptor {
            val value = actual.networking.socket(SupervisorSocketUtils.AF_UNIX, SupervisorSocketUtils.SOCK_STREAM, 0).getOrThrow("acquire cleanup fixture socket")
            val fd = FileDescriptor.adopt(value.toInt(), FileDescriptorRole.UnixSocket)
            acquired += fd
            val field = ProfilerSession::class.java.getDeclaredField("listeners").apply { isAccessible = true }
            val listeners = field.get(session) as MutableList<ProfilerTraceListener>
            listeners += ProfilerTraceListener(fd, CopyOnWriteArrayList(), null, ConcurrentHashMap(), { null }, native)
            return fd
        }

        @Suppress("UNCHECKED_CAST")
        fun sessions(): Set<ProfilerSession> =
            ProfilerService::class.java
            .getDeclaredField("sessions")
            .apply { isAccessible = true }
            .get(service) as Set<ProfilerSession>

        override fun close() {
            acquired.filter { it.isLiveForIo() }.forEach { closeNative(it).getOrThrow("release cleanup fixture socket") }
        }
    }
}
