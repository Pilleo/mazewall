package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertTrue

@NeedsFreshJvm
class MemfdCreateBypassTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `NO_EXEC blocks memfd_create as of recent security update`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    ContainedExecutors.installOnCurrentThread(Policy.NO_EXEC)

                    val arch = Arch.current()
                    // memfd_create IS blocked by NO_EXEC
                    val res =
                        nativeScope {
                            val name = allocateFrom("test_memfd")

LinuxNative.raw.syscall(
    arch.memfdCreate.toLong(),
    NativeArg.MemoryArg(name),
    NativeArg.NullArg,
    NativeArg.NullArg,
)
                        }
                    assertTrue(res is LinuxNative.SyscallResult.Error && res.errno == NativeConstants.EPERM, "Expected EPERM, got $res")
                }.get()
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `memfd_create is blocked when explicitly added to policy`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    val combined =
                        ProcessPolicyBuilder
                            .denyList(RuntimeProfile.NATIVE_IMAGE)
                            .block(Syscall.MEMFD_CREATE)
                            .build()

                    ContainedExecutors.installOnCurrentThread(combined)

                    val arch = Arch.current()
                    val res =
                        nativeScope {
                            val name = allocateFrom("test_memfd_blocked")

LinuxNative.raw.syscall(
    arch.memfdCreate.toLong(),
    NativeArg.MemoryArg(name),
    NativeArg.NullArg,
    NativeArg.NullArg,
)
                        }
                    assertTrue(res is LinuxNative.SyscallResult.Error && res.errno == NativeConstants.EPERM, "Expected EPERM, got $res")
                }.get()
        } finally {
            executor.shutdown()
        }
    }
}
