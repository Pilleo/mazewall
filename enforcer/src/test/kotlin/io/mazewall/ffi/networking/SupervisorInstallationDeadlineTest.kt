package io.mazewall.ffi.networking

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.TestFileDescriptors
import io.mazewall.core.testAcquisition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SupervisorInstallationDeadlineTest {
    @Test fun `missing readiness fails within the installation budget and preserves applied stage`() {
        scenario(stallConnect = false)
    }

    @Test fun `late connection is closed without starting a listener after installation expires`() {
        scenario(stallConnect = true)
    }

    @Test fun `timeout does not close a listener claimed by an active descriptor send`() {
        scenario(stallConnect = false, stallSend = true)
    }

    private fun scenario(
        stallConnect: Boolean,
        stallSend: Boolean = false,
    ) {
        val property = "mazewall.supervisor.installTimeoutMillis"
        val previous = System.getProperty(property)
        System.setProperty(property, "200")
        val ready = AtomicReference<CountDownLatch>()
        val releaseConnect = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val applied = AtomicInteger()
        val callbacks = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val closed = CopyOnWriteArrayList<Int>()
        val native = MockNativeEngine().apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(62001L).testAcquisition() }
            fileSystem.onClose = { fd ->
                closed += fd.value
                if (fd.value == 62002) socketClosed.countDown()
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        val socket = TestFileDescriptors.replace<FileDescriptorRole.UnixSocket>(62002)
        val filter = BpfFilter.build(Arch.current(), Policy.builder().build().definition)
        val installer = Thread {
            try {
                SupervisorSeccompNotifInstaller.installOwned(
                    native,
                    true,
                    "/unused",
                    filter,
                    false,
                    connectWithRetry = {
                        if (!stallSend) entered.countDown()
                        if (stallConnect) releaseConnect.await()
                        socket
                    },
                    sendDescriptor = { _, _ ->
                        if (stallSend) {
                            entered.countDown()
                            releaseSend.await()
                        }
                        true
                    },
                    onFilterApplied = { applied.incrementAndGet() },
                    onSocketConnected = { _, latch ->
                        callbacks.incrementAndGet()
                        // The callback owns cleanup but deliberately never signals readiness.
                        ready.set(latch)
                    },
                )
            } catch (problem: Throwable) {
                failure.set(problem)
            } finally {
                completed.countDown()
            }
        }
        try {
            installer.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(completed.await(2, TimeUnit.SECONDS), "installer must stop waiting when its deadline expires")
            assertNotNull(failure.get())
            assertEquals(1, applied.get(), "irreversible filter application remains published")
            assertEquals(if (stallSend) 0 else 1, closed.count { it == 62001 })
            if (stallConnect || stallSend) {
                releaseConnect.countDown()
                releaseSend.countDown()
                assertTrue(socketClosed.await(5, TimeUnit.SECONDS))
                assertEquals(0, callbacks.get())
                assertEquals(1, closed.count { it == 62002 })
                assertEquals(1, closed.count { it == 62001 })
            }
        } finally {
            releaseConnect.countDown()
            releaseSend.countDown()
            ready.get()?.countDown()
            installer.join(5000)
            if (socket.isValid) native.fileSystem.close(socket)
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
