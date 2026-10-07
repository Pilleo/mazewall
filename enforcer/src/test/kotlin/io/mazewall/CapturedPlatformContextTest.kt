package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapturedPlatformContextTest {
    @Test
    fun `explicit prepared environment is reused after global replacement`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        LinuxNative.setEngine(
            MockNativeEngine().apply {
            process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
        },
        )
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)
        })
        val environment = InstallationEnvironment.capture()
        val foreignCalls = AtomicInteger()
        LinuxNative.setEngine(
            MockNativeEngine().apply {
            process.onPrctl = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
        },
        )
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Windows"
        })
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()

        Executors.newSingleThreadExecutor().use { worker ->
            assertTrue(
                worker
                    .submit(
                    Callable {
                ContainedExecutors.installOnCurrentThread(policy.definition, DefaultStacktraceScopingPolicy, environment).use { receipt ->
                    receipt.requireInstalled()
                    assertEquals(Platform.FallbackBehavior.FAIL, receipt.fallback)
                    receipt.installed
                }
            },
                ).get(5, TimeUnit.SECONDS),
            )
        }
        assertEquals(0, foreignCalls.get())
    }

    @Test
    fun `dedicated preflight cannot replace its required installation fallback`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        LinuxNative.setEngine(
            MockNativeEngine().apply {
            process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
            onSyscall = { nr, _, _, flags, _, _, _ ->
                LinuxNative.SyscallResult.Success(
                    if (nr == 444L && flags.asLong == 1L) {
                    8L
                } else if (nr == 444L) {
                    1000L
                } else {
                    0L
                },
                )
            }
        },
        )
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun probeSeccompTsync(): Boolean {
                System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
                return true
            }

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = false
        })
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()

        ContainedExecutors.installOnDedicatedProcess(policy).use { receipt ->
            assertEquals(Platform.FallbackBehavior.FAIL, receipt.fallback)
            receipt.requireInstalled()
        }
    }

    @AfterEach
    fun restoreRuntime() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun `real feature probes retain installation native after support callback replacement`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        Platform.resetToDefault()
        Platform.isCpuCetSupportedOverride = false
        val foreignCalls = AtomicInteger()
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
        val selected = MockNativeEngine().apply {
            process.onPrctl = { command ->
                if (command is PrctlCommand.SetSeccomp) {
                    LinuxNative.setEngine(foreign)
                    LinuxNative.SyscallResult.Error(22, -1L)
                } else {
                    LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
                }
            }
            onSyscall = { nr, _, _, flags, _, _, _ ->
                when {
                    nr == 444L && flags.asLong == 1L -> LinuxNative.SyscallResult.Success(8L)
                    nr == 444L -> LinuxNative.SyscallResult.Success(1000L)
                    nr == Arch.current().seccompSyscallNumber.toLong() && flags.asLong == 0L -> LinuxNative.SyscallResult.Error(14, -1L)
                    else -> LinuxNative.SyscallResult.Success(0L)
                }
            }
        }
        LinuxNative.setEngine(selected)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()

        ContainedExecutors.installOnCurrentThread(policy).use { receipt ->
            receipt.requireInstalled()
            assertTrue(receipt.landlockApplied)
        }
        assertEquals(0, foreignCalls.get())
    }

    @Test
    fun `support callback cannot switch the selected platform provider`() {
        System.setProperty("io.mazewall.fallback", "FAIL")
        LinuxNative.setEngine(
            MockNativeEngine().apply {
            process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
        },
        )
        val foreign = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Windows"

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(13, -1L)
        }
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean {
                Platform.setProvider(foreign)
                return true
            }

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)
        })

        ContainedExecutors.installOnCurrentThread(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()).use { it.requireInstalled() }
    }
}
