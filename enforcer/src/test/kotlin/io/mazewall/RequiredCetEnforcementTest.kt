package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequiredCetEnforcementTest {
    private var enabled = 1L
    private var lockError: Int? = null
    private var statusError: Int? = null
    private var lockReturn = 0L
    private var statusReturn = 0L
    private var enableCalls = 0
    private var lockCalls = 0
    private var afterLockStatusError: Int? = null
    private var afterLockAction: () -> Unit = {}

    @BeforeEach
    fun prepareRuntime() {
        org.junit.jupiter.api.Assumptions
            .assumeTrue(Arch.current() == Arch.AMD64, "CET shadow-stack UAPI is x86_64 specific")
        val process = object : MockNativeProcess() {
            override fun archPrctl(
                code: Int,
                addr: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                when (code) {
                    0x5001 -> {
                        enableCalls++
                        enabled = 1L
                        LinuxNative.SyscallResult.Success(0L)
                    }
                    0x5003 -> {
                        lockCalls++
                        afterLockAction()
                        lockError?.let { LinuxNative.SyscallResult.Error(it, -1L) } ?: LinuxNative.SyscallResult.Success(lockReturn)
                    }
                    else -> error("Unexpected CET mutation operation: $code")
                }

            override fun archPrctl(
                code: Int,
                addr: ManagedSegment,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                check(code == 0x5005) { "Status must use the Linux status operation" }
                if (lockCalls > 0) afterLockStatusError?.let { return LinuxNative.SyscallResult.Error(it, -1L) }
                statusError?.let { return LinuxNative.SyscallResult.Error(it, -1L) }
                addr.writeLong(0, enabled)
                return LinuxNative.SyscallResult.Success(statusReturn)
            }
        }
        process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
        LinuxNative.setEngine(MockNativeEngine(process = process))
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun getLandlockAbiVersion(): Int = 8

            override fun probeSeccompTsync(): Boolean = true

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = true
        })
        Platform.isCpuCetSupportedOverride = true
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
    @EnumSource(Platform.FallbackBehavior::class)
    fun `inactive shadow stack is rejected without late FFM enablement`(fallback: Platform.FallbackBehavior) {
        enabled = 0L
        assertTargetRejected(fallback)
        assertEquals(0, enableCalls)
        assertEquals(0, lockCalls)
    }

    @ParameterizedTest
    @EnumSource(Platform.FallbackBehavior::class)
    fun `lock EPERM plus enabled status never authorizes CET execution`(fallback: Platform.FallbackBehavior) {
        lockError = 1
        assertTargetRejected(fallback)
    }

    @ParameterizedTest
    @EnumSource(Platform.FallbackBehavior::class)
    fun `lock EACCES never authorizes CET execution`(fallback: Platform.FallbackBehavior) {
        lockError = 13
        assertTargetRejected(fallback)
    }

    @Test
    fun `unexpected nonzero lock success cannot confirm locking`() {
        lockReturn = 1L
        assertTargetRejected(Platform.FallbackBehavior.FAIL)
    }

    @Test
    fun `unexpected nonzero status success cannot authorize a read mask`() {
        statusReturn = 1L
        assertTargetRejected(Platform.FallbackBehavior.FAIL)
    }

    @Test
    fun `status permission failure remains visible instead of inactive status`() {
        statusError = 13
        val failure = assertFailsWith<IllegalStateException> { Platform.queryIntelCetStatus() }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains("errno=13") })
    }

    @Test
    fun `pre-enabled shadow stack records verified thread proof and reuses it without masking EPERM`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()
        ContainedExecutors.installOnCurrentThread(policy).use { first ->
            first.requireInstalled()
            assertEquals(CetOutcome.VERIFIED_CURRENT_THREAD, first.cetOutcome)
            assertEquals(true, first.progress?.cetLocked)
        }
        lockError = 1
        ContainedExecutors.installOnCurrentThread(policy).use { second ->
            second.requireInstalled()
            assertEquals(CetOutcome.VERIFIED_CURRENT_THREAD, second.cetOutcome)
            assertEquals(false, second.progress?.cetLocked)
        }
        assertEquals(1, lockCalls)
        assertEquals(0, enableCalls)
        Executors.newSingleThreadExecutor().use { other ->
            assertFailsWith<ExecutionException> { other.submit(Callable { ContainedExecutors.installOnCurrentThread(policy) }).get(5, TimeUnit.SECONDS) }
        }
        assertEquals(2, lockCalls, "Another thread must establish its own proof")
    }

    @Test
    fun `successful lock followed by failed status retains progress and quarantines the thread`() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        afterLockStatusError = 13
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()
        val receipt = ContainedExecutors.installOnCurrentThread(policy)
        assertFalse(receipt.installed)
        assertEquals(CetOutcome.UNCONFIRMED, receipt.cetOutcome)
        assertEquals(true, receipt.progress?.cetLocked)
        val failure = assertNotNull(receipt.failure)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains("errno=13") })
        assertSame(failure, assertFailsWith<IllegalStateException> { receipt.requireInstalled() })
        afterLockStatusError = null
        assertSame(failure, assertFailsWith<IllegalStateException> { ContainedExecutors.installOnCurrentThread(policy) })
    }

    @Test
    fun `required receipt rejects missing CET proof even with installed seccomp`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()
        assertFailsWith<IllegalStateException> { InstallationReceipt(false, policy.definition, installed = true).requireInstalled() }
        assertFailsWith<IllegalStateException> {
            InstallationReceipt(true, policy.definition, installed = true, cetOutcome = CetOutcome.VERIFIED_CURRENT_THREAD).requireInstalled()
        }
    }

    @Test
    fun `CET and plain seccomp use the backend captured before feature setup`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        val foreignCalls = java.util.concurrent.atomic
            .AtomicInteger()
        val foreign = MockNativeEngine().apply {
            onSyscall = { _, _, _, _, _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
            process.onPrctl = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
        }
        afterLockAction = { LinuxNative.setEngine(foreign) }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()

        ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
            receipt.requireInstalled()
            assertEquals(CetOutcome.VERIFIED_CURRENT_THREAD, receipt.cetOutcome)
        }
        assertEquals(0, foreignCalls.get())
    }

    @Test
    fun `process-wide CET coverage is rejected without enabling or locking`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyProcessCetRejection")
    }

    fun verifyProcessCetRejection() {
        prepareRuntime()
        try {
            System.setProperty("io.mazewall.fallback", "FAIL")
            val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()
            assertFailsWith<IllegalStateException> { ContainedExecutors.installOnProcess(policy) }
            assertEquals(0, enableCalls)
            assertEquals(0, lockCalls)
            assertEquals(0, ContainmentStateRegistry.processState.filterDepth)
        } finally {
            restoreRuntime()
        }
    }

    private fun assertTargetRejected(fallback: Platform.FallbackBehavior) {
        System.setProperty("io.mazewall.fallback", fallback.name)
        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).lockIntelCet().build()
        Executors.newSingleThreadExecutor().use { worker ->
            assertFailsWith<ExecutionException> {
                ContainedExecutors.wrap(worker, policy).submit(Callable { calls.incrementAndGet() }).get(5, TimeUnit.SECONDS)
            }
        }
        assertEquals(0, calls.get())
    }
}
