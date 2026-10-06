package io.mazewall.enforcer.internal

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

class RequiredTaskEntryTest {
    enum class Failure {
        SECCOMP_UNAVAILABLE,
        LANDLOCK_UNAVAILABLE,
        SECCOMP_DENIED,
        LANDLOCK_THEN_SECCOMP_DENIED,
    }

    @AfterEach
    fun restoreRuntime() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `required task never enters after missing or failed installation`(
        fallback: Platform.FallbackBehavior,
        failure: Failure,
    ) {
        System.setProperty("io.mazewall.fallback", fallback.name)
        val engine = MockNativeEngine()
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetSeccomp && failure in setOf(Failure.SECCOMP_DENIED, Failure.LANDLOCK_THEN_SECCOMP_DENIED)) {
                LinuxNative.SyscallResult.Error(1, -1L)
            } else {
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
        }
        val seccompNr = Arch.current().seccompSyscallNumber.toLong()
        engine.onSyscall = { nr, _, _, _, _, _, _ ->
            if (nr == seccompNr && failure in setOf(Failure.SECCOMP_DENIED, Failure.LANDLOCK_THEN_SECCOMP_DENIED)) {
                LinuxNative.SyscallResult.Error(1, -1L)
            } else {
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        LinuxNative.setEngine(engine)
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = failure != Failure.SECCOMP_UNAVAILABLE

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockAbiVersion(): Int = if (failure == Failure.LANDLOCK_UNAVAILABLE) 0 else 5

            override fun getLandlockCapability(): KernelCapability<Int> = if (failure == Failure.LANDLOCK_UNAVAILABLE) KernelCapability.Unsupported else KernelCapability.Available(5)

            override fun probeSeccompTsync(): Boolean = true

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = false
        })
        val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.EXECVE)
        if (failure in setOf(Failure.LANDLOCK_UNAVAILABLE, Failure.LANDLOCK_THEN_SECCOMP_DENIED)) {
            builder.filesystem(FileSystemPolicy.DenyAll)
        }
        val calls = AtomicInteger()
        val delegate = Executors.newSingleThreadExecutor()
        try {
            val wrapper = ContainedExecutorWrapper(delegate, builder.build().definition)
            val thrown = assertFailsWith<ExecutionException> {
                wrapper.submit(Callable { calls.incrementAndGet() }).get(5, TimeUnit.SECONDS)
            }
            assertNotNull(thrown.cause)
            assertEquals(0, calls.get(), "Fallback cannot authorize entry into a required contained task")
            if (failure == Failure.LANDLOCK_THEN_SECCOMP_DENIED) {
                val remainsRestricted = delegate.submit(Callable { ContainmentStateRegistry.threadState.landlockPolicy != null })
                assertEquals(true, remainsRestricted.get(5, TimeUnit.SECONDS), "Failed Seccomp cannot rewind applied Landlock")
            }
        } finally {
            delegate.shutdown()
            check(delegate.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        @JvmStatic
        fun failures(): List<Arguments> =
            Platform.FallbackBehavior.entries.flatMap { fallback ->
            Failure.entries.map { failure -> Arguments.of(fallback, failure) }
        }
    }
}
