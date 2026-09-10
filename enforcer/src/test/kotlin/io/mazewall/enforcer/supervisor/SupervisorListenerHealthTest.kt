package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.ffi.memory.writeByte
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SupervisorListenerHealthTest {
    @Test fun `failed thread start closes its accepted socket`() {
        var closes = 0
        val native = MockNativeEngine().apply {
            fileSystem.onClose = {
                closes++
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        val socket = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(62005)
        val rejected = IllegalStateException("cannot launch listener")
        LinuxNative.setEngine(native)
        try {
            val listener = JVMValidationListener(socket, DefaultStacktraceScopingPolicy, launch = { throw rejected })
            assertSame(rejected, assertFailsWith<IllegalStateException> { listener.start(CountDownLatch(1)) })
            assertEquals(1, closes)
        } finally {
            if (socket.isValid) native.fileSystem.close(socket)
            LinuxNative.resetToDefault()
        }
    }

    @Test fun `malformed handshake publishes failure before releasing readiness`() {
        val enteredFailure = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val finishedFailure = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val native = MockNativeEngine().apply {
            pollResult = LinuxNative.SyscallResult.Success(1L)
            memory.onRead = { _, buffer, _ ->
                buffer.writeByte(0, 0)
                LinuxNative.SyscallResult.Success(1L)
            }
        }
        LinuxNative.setEngine(native)
        try {
            JVMValidationListener(
                TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(62006),
                DefaultStacktraceScopingPolicy,
                onUnexpectedTermination = {
                    enteredFailure.countDown()
                    releaseFailure.await()
                    finishedFailure.countDown()
                },
            ).start(ready)
            assertTrue(enteredFailure.await(5, TimeUnit.SECONDS))
            assertEquals(1L, ready.count, "malformed ACK must not race readiness ahead of failure")
        } finally {
            releaseFailure.countDown()
            finishedFailure.await(5, TimeUnit.SECONDS)
            LinuxNative.resetToDefault()
        }
    }

    @Test fun `socket close failure cannot hide an unexpected listener exit`() {
        val reported = CountDownLatch(1)
        val attemptedClose = CountDownLatch(1)
        val runtime = SupervisorRuntimeContext()
        val lifetime = SupervisorInstallationLifetime(Thread.currentThread(), runtime)
        val native = MockNativeEngine().apply {
            pollResult = LinuxNative.SyscallResult.Success(1L)
            memory.onRead = { _, _, _ -> LinuxNative.SyscallResult.Success(0L) }
            fileSystem.onClose = {
                attemptedClose.countDown()
                LinuxNative.SyscallResult.Error(5, -1L)
            }
        }
        LinuxNative.setEngine(native)
        try {
            JVMValidationListener(
                TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(62004),
                DefaultStacktraceScopingPolicy,
                runtime,
                onUnexpectedTermination = {
                    lifetime.fail(it)
                    reported.countDown()
                },
            ).start(CountDownLatch(1))
            assertTrue(attemptedClose.await(5, TimeUnit.SECONDS))
            assertTrue(reported.await(1, TimeUnit.SECONDS), "listener failure must be published despite cleanup failure")
            assertFailsWith<IllegalStateException> { lifetime.requireReusable() }
        } finally {
            lifetime.close()
            LinuxNative.resetToDefault()
        }
    }
}
