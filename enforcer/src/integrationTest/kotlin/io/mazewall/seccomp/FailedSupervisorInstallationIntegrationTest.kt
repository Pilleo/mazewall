package io.mazewall.seccomp

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.PartialInstallationException
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.internal.supervisor.*
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
            override val handlers = mapOf<Syscall, (Tid, List<Any>, List<StackTraceElement>) -> Boolean>(
                Syscall.CONNECT to { _, _, _ -> true },
            )
        }
        val executed = AtomicInteger()
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowMmapExec().build()
        try {
            Executors.newSingleThreadExecutor().use { worker ->
                val initial = assertFailsWith<ExecutionException> {
                    worker
                        .submit {
                        ContainedExecutors.installOnCurrentThread(policy, scoping).use { receipt ->
                            receipt.requireInstalled()
                            executed.incrementAndGet()
                        }
                    }.get(10, TimeUnit.SECONDS)
                }
                assertTrue(assertIs<PartialInstallationException>(initial.cause, initial.stackTraceToString()).progress.seccompApplied, initial.stackTraceToString())
                assertEquals(1, worker.submit<Int> { ContainmentStateRegistry.threadState.filterDepth }.get(10, TimeUnit.SECONDS))
                assertTrue(failedCalls.get() > 0)
                val setupCalls = failedCalls.get()
                val retry = assertFailsWith<ExecutionException> {
                    worker
                        .submit {
                        ContainedExecutors.installOnCurrentThread(policy, scoping).use { receipt ->
                            receipt.requireInstalled()
                            executed.incrementAndGet()
                        }
                    }.get(10, TimeUnit.SECONDS)
                }
                assertIs<IllegalStateException>(retry.cause)
                assertSame(initial.cause, retry.cause, "quarantine must preserve the original installation failure")
                val plainRejected = assertFailsWith<ExecutionException> {
                    worker
                        .submit {
                        ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
                            receipt.requireInstalled()
                            executed.incrementAndGet()
                        }
                    }.get(10, TimeUnit.SECONDS)
                }
                assertSame(initial.cause, plainRejected.cause)
                assertEquals(setupCalls, failedCalls.get(), "retry must fail before attempting another handshake")
                assertEquals(0, executed.get(), "a failed permanent installation must never authorize a later task")
                assertEquals(1, worker.submit<Int> { ContainmentStateRegistry.threadState.filterDepth }.get(10, TimeUnit.SECONDS))
            }
        } finally {
            LinuxNative.setEngine(native)
            manager.stop()
        }
    }
}
