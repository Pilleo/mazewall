package io.mazewall.ffi.networking

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.Test
import kotlin.test.*

class SupervisorInstallationFailureTest {
    @Test fun `failed NEW_LISTENER cannot return success or publish an installed stage`() {
        val native = MockNativeEngine().apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Error(22, -1L) }
        }
        var applied = false
        val failure = assertFailsWith<IllegalStateException> {
            SupervisorSeccompNotifInstaller.installOwned(
                native = native,
                userNotifSupported = true,
                socketPath = "/unused",
                filter = io.mazewall.enforcer.internal.seccomp.BpfFilter.build(
                    Arch.current(),
                    io.mazewall.enforcer.api.Policy
                    .builder()
                    .build()
                    .definition,
                ),
                processWide = false,
                connectWithRetry = { error("must not connect") },
                sendDescriptor = { _, _ -> error("must not send") },
                onFilterApplied = { applied = true },
                onSocketConnected = { _, _ -> error("must not initialize listener") },
            )
        }
        assertTrue(failure.message.orEmpty().contains("errno 22"))
        assertFalse(applied)
    }
}
