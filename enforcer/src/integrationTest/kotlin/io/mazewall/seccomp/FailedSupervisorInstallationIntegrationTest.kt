package io.mazewall.seccomp

import io.mazewall.*
import io.mazewall.core.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.PartialInstallationException
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.enforcer.supervisor.*
import io.mazewall.ffi.memory.ManagedSegment
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@NeedsFreshJvm
class FailedSupervisorInstallationIntegrationTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `connection failure after native installation prevents same worker retry`() {
        assertFailedInstallationCannotRunTask(failConnection = true)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `descriptor transfer failure after native installation prevents same worker retry`() {
        assertFailedInstallationCannotRunTask(failConnection = false)
    }

    private fun assertFailedInstallationCannotRunTask(failConnection: Boolean) {
        val manager = SupervisorDaemonManager.getInstance()
        manager.getOrSpawnSharedDaemon()
        val native = LinuxNative.snapshot()
        val failedCalls = AtomicInteger()
        LinuxNative.setEngine(object : NativeEngine by native {
            override val networking = object : NativeNetworking by native.networking {
                override fun connect(
                    sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    addr: ManagedSegment,
                    addrlen: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    if (failConnection) {
                        failedCalls.incrementAndGet()
                        LinuxNative.SyscallResult.Error(13, -1L)
                    } else {
                        native.networking.connect(sockfd, addr, addrlen)
                    }

                override fun sendmsg(
                    sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    msg: ManagedSegment,
                    flags: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    failedCalls.incrementAndGet()
                    return LinuxNative.SyscallResult.Error(5, -1L)
                }
            }
        })
        val scoping = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(Syscall.GETPPID to { _, _, _ -> true })
        }
        val executed = AtomicInteger()
        try {
            Executors.newSingleThreadExecutor().use { worker ->
                val wrapped = ContainedExecutors.wrap(worker, Policy.builder().allowMmapExec().build(), scoping)
                val initial = assertFailsWith<ExecutionException> { wrapped.submit { executed.incrementAndGet() }.get() }
                assertTrue(assertIs<PartialInstallationException>(initial.cause).progress.seccompApplied)
                assertEquals(1, worker.submit<Int> { ContainmentStateRegistry.threadState.filterDepth }.get())
                assertTrue(failedCalls.get() > 0)
                val setupCalls = failedCalls.get()
                val retry = assertFailsWith<ExecutionException> { wrapped.submit { executed.incrementAndGet() }.get() }
                assertIs<IllegalStateException>(retry.cause)
                assertTrue(
                    retry.cause
                    ?.message
                    .orEmpty()
                    .contains("Permanent supervisor installation"),
                )
                val plain = ContainedExecutors.wrap(worker, Policy.builder().allowMmapExec().build())
                assertFailsWith<ExecutionException> { plain.submit { executed.incrementAndGet() }.get() }
                assertEquals(setupCalls, failedCalls.get(), "retry must fail before attempting another handshake")
                assertEquals(0, executed.get(), "a failed permanent installation must never authorize a later task")
                assertEquals(1, worker.submit<Int> { ContainmentStateRegistry.threadState.filterDepth }.get())
            }
        } finally {
            LinuxNative.setEngine(native)
            manager.stop()
        }
    }
}
