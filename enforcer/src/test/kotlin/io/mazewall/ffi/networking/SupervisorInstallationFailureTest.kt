package io.mazewall.ffi.networking

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.core.Arch
import org.junit.jupiter.api.Test
import kotlin.test.*

class SupervisorInstallationFailureTest {
    @Test fun `failed NEW_LISTENER cannot return success or publish an installed stage`() {
        val native = MockNativeEngine().apply {
            onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Error(22, -1L) }
        }
        var applied = false
        val failure = assertFailsWith<IllegalStateException> {
            SupervisorSeccompNotifInstaller.install(
                native = native,
                userNotifSupported = true,
                socketPath = "/unused",
                filter = io.mazewall.BpfFilter.build(
                    Arch.current(),
                    io.mazewall.Policy
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
