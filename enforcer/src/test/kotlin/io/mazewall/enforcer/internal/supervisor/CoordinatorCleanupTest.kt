package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeFileSystem
import io.mazewall.ffi.networking.SeccompInstallHandshake
import io.mazewall.ffi.networking.SeccompInstallStage
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.getFdOrThrow
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises the installer's private ownership aggregator without fabricating descriptors. */
class CoordinatorCleanupTest {
    private val type = Class.forName("io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller\$CoordinatorCleanup")

    private fun owner(): Any =
        type
            .getDeclaredConstructor(Throwable::class.java)
            .apply { isAccessible = true }
            .newInstance(null)

    private fun invoke(
        owner: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method = type.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try {
            method.invoke(owner, *args)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun attempt(
        owner: Any,
        action: () -> Unit,
    ) {
        invoke(owner, "attempt", action)
    }

    @Test
    fun `direct Throwable cannot skip either owner cleanup`() {
        val owner = owner()
        val original = Throwable("direct throwable")
        var listenerCloses = 0
        var socketCloses = 0
        attempt(owner) { throw original }
        attempt(owner) { listenerCloses++ }
        attempt(owner) { socketCloses++ }
        assertSame(original, runCatching { invoke(owner, "rethrow") }.exceptionOrNull())
        assertEquals(1, listenerCloses)
        assertEquals(1, socketCloses)
    }

    @Test
    fun `fatal close outranks direct Throwable while later cleanup still runs`() {
        val owner = owner()
        val original = Throwable("transfer failed")
        val fatal = AssertionError("listener close failed")
        var socketCloses = 0
        attempt(owner) { throw original }
        attempt(owner) { throw fatal }
        attempt(owner) { socketCloses++ }
        assertSame(fatal, runCatching { invoke(owner, "rethrow") }.exceptionOrNull())
        assertTrue(fatal.suppressed.any { it === original })
        assertEquals(1, socketCloses)
    }

    @Test
    fun `late coordinator fatal remains observable after first handshake failure`() {
        val handshake = SeccompInstallHandshake({})
        val original = IllegalStateException("first failure")
        val fatal = AssertionError("late close failure")
        handshake.fail(original)
        val cleanup = owner()
        var closeCount = 0
        attempt(cleanup) {
            closeCount++
            throw fatal
        }
        assertSame(fatal, runCatching { invoke(cleanup, "report", handshake) }.exceptionOrNull())
        assertSame(original, runCatching { handshake.checkActive() }.exceptionOrNull())
        assertTrue(fatal.suppressed.any { it === original })
        assertEquals(1, closeCount)
    }

    @Test
    fun `tracee cleanup promotes fatal without replacing first handshake failure`() {
        val real = LinuxNative.snapshot()
        val listener = real.networking
            .socket(1, 1 or io.mazewall.ffi.NativeConstants.SOCK_CLOEXEC, 0)
            .getFdOrThrow("cleanup test listener", FileDescriptorRole.SeccompNotif)
        val original = Throwable("initial failure")
        val fatal = AssertionError("listener close failed")
        val closes = AtomicInteger()
        val publications = AtomicInteger()
        val native = MockNativeEngine(
            fileSystem = object : MockNativeFileSystem() {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes.incrementAndGet()
                    real.fileSystem
                        .close(fd)
                        .getOrThrow("close test listener")
                    throw fatal
                }
            },
        )
        val handshake = SeccompInstallHandshake({ publications.incrementAndGet() })
        handshake.advance(SeccompInstallStage.Applied)
        val reference = AtomicReference(listener)
        val cleanup = SupervisorSeccompNotifInstaller::class.java
            .declaredMethods
            .single { it.name == "cleanupFailedInstallation" }
            .apply { isAccessible = true }
        val result = cleanup.invoke(SupervisorSeccompNotifInstaller, handshake, reference, native, original)
        assertSame(fatal, result)
        assertTrue(fatal.suppressed.any { it === original })
        assertSame(original, runCatching { handshake.checkActive() }.exceptionOrNull())
        assertEquals(1, closes.get())
        assertEquals(1, publications.get())
        assertEquals(null, reference.get())
    }

    @Test
    fun `interruption is retained and cleanup still runs`() {
        val ended = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val flag = AtomicReference(false)
        val closed = AtomicReference(false)
        val thread = Thread {
            val owner = owner()
            val interrupted = InterruptedException("interrupted")
            attempt(owner) { throw interrupted }
            attempt(owner) { closed.set(true) }
            result.set(runCatching { invoke(owner, "rethrow") }.exceptionOrNull())
            flag.set(Thread.currentThread().isInterrupted)
            ended.countDown()
        }
        thread.start()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        thread.join()
        assertTrue(result.get() is InterruptedException)
        assertTrue(flag.get())
        assertTrue(closed.get())
    }
}
