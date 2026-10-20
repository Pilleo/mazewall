package io.mazewall.enforcer

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.KernelCapability
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PlatformProvider
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.PrctlCommand
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallationBootstrapTest {
    @Test
    fun `cold unsupervised installation links no callback after seccomp commit`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyColdReturn")
    }

    fun verifyColdReturn() {
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"

                override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(5)

                override fun hasKernelSeccompSupport(): Boolean = true

                override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    return LinuxNative.SyscallResult.Error(22, -1L)
                }
            },
        )
        val native = MockNativeEngine()
        native.process.onPrctl = { command ->
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        var installedAt: Instant? = null
        native.onSyscall = { nr, operation, _, _, _, _, _ ->
            if (nr == Arch.current().seccompSyscallNumber.toLong() &&
                operation.asLong == NativeConstants.SECCOMP_SET_MODE_FILTER.toLong() &&
                installedAt == null
            ) {
                installedAt = Instant.now()
            }
            LinuxNative.SyscallResult.Success(0L)
        }
        LinuxNative.setEngine(native)
        val recordingFile = Files.createTempFile("installation-bootstrap-", ".jfr")
        try {
            Recording().use { recording ->
                recording.enable("jdk.ClassLoad")
                recording.start()
                ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()).use { receipt ->
                    receipt.requireInstalled()
                }
                recording.stop()
                recording.dump(recordingFile)
            }
            val boundary = assertNotNull(installedAt, "actual install callback was not reached")
            val lateCallbacks = RecordingFile
                .readAllEvents(recordingFile)
                .filter { it.eventType.name == "jdk.ClassLoad" && !it.startTime.isBefore(boundary) }
                .map { it.getClass("loadedClass").name }
                .filter { it.startsWith("io.mazewall.enforcer.api.ContainedExecutors\$\$Lambda") }
            assertTrue(lateCallbacks.isEmpty(), "Callback linkage after irreversible installation: $lateCallbacks")
        } finally {
            LinuxNative.resetToDefault()
            Platform.resetToDefault()
            Files.deleteIfExists(recordingFile)
        }
    }
}
