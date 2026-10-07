package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapturedLandlockBackendNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `captured Landlock backend denies a real file after global replacement`() {
        val file = Files.createTempFile("captured-landlock-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCapturedLandlock", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyCapturedLandlock(file: String) {
        verifyCapturedInstallation(file, supervised = false)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `captured backend spans Landlock and supervised installation`() {
        val file = Files.createTempFile("captured-supervised-landlock-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCapturedSupervisedInstallation", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyCapturedSupervisedInstallation(file: String) {
        verifyCapturedInstallation(file, supervised = true)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `captured provider spans Landlock and supervised installation`() {
        val file = Files.createTempFile("captured-platform-landlock-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCapturedPlatformInstallation", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyCapturedPlatformInstallation(file: String) {
        verifyCapturedInstallation(file, supervised = true, replaceProvider = true)
    }

    private fun verifyCapturedInstallation(
        file: String,
        supervised: Boolean,
        replaceProvider: Boolean = false,
    ) {
        System.setProperty("io.mazewall.fallback", "FAIL")
        val actual = LinuxNative.snapshot()
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
            fileSystem.onOpen = { _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
            fileSystem.onClose = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        val selectedRaw = object : RawSyscallOperations by actual.raw {
            override fun syscall(
                nr: Long,
                a1: NativeArg,
                a2: NativeArg,
                a3: NativeArg,
                a4: NativeArg,
                a5: NativeArg,
                a6: NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val result = actual.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                if (nr == 444L && result is LinuxNative.SyscallResult.Success && result.value >= 0L && a3.asLong == 0L) {
                    LinuxNative.setEngine(foreign)
                    if (replaceProvider) {
                        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
                        override fun getOsName(): String = "Windows"

                        override fun probeSeccompUserNotif(): Boolean = false

                        override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Rejected(13)
                    })
                    }
                }
                return result
            }
        }
        LinuxNative.setEngine(object : NativeEngine by actual {
            override val raw: RawSyscallOperations = selectedRaw
        })
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        try {
            Executors.newSingleThreadExecutor().use { worker ->
                val failure = assertFailsWith<ExecutionException> {
                    val contained = if (supervised) {
                        val scoping = object : StacktraceScopingPolicy {
                            override val handlers = mapOf<io.mazewall.platform.syscall.Syscall, (io.mazewall.platform.lifecycle.Tid, List<Any>, List<StackTraceElement>) -> Boolean>(
                                io.mazewall.platform.syscall.Syscall.CONNECT to { _, _, _ -> true },
                            )
                        }
                        ContainedExecutors.wrap(worker, policy, scoping)
                    } else {
                        ContainedExecutors.wrap(worker, policy)
                    }
                    contained.submit(Callable { Files.readString(Path.of(file)) }).get(20, TimeUnit.SECONDS)
                }
                assertTrue(
                    generateSequence<Throwable>(failure) { it.cause }.any { it is java.nio.file.AccessDeniedException },
                    "Actual fresh file open must be denied, not setup fail from the replaced backend: ${failure.stackTraceToString()}",
                )
            }
            assertEquals(0, foreignCalls.get())
            assertEquals("caller-readable", Files.readString(Path.of(file)))
        } finally {
            Platform.resetToDefault()
            LinuxNative.resetToDefault()
        }
    }
}
