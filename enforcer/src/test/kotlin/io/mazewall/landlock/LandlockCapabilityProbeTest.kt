package io.mazewall.landlock

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LandlockCapabilityProbeTest {
    @Test fun `classifies supported abi`() = assertEquals(LandlockCapabilityProbe.Outcome.Supported(1), LandlockCapabilityProbe.classify(LinuxNative.SyscallResult.Success(1L)))

    @Test fun `classifies missing kernel support`() =
        assertEquals(LandlockCapabilityProbe.Outcome.Unsupported, LandlockCapabilityProbe.classify(LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState>(NativeConstants.ENOSYS, -1)))

    @Test fun `preserves permission rejection`() {
        assertIs<LandlockCapabilityProbe.Outcome.Rejected>(
            LandlockCapabilityProbe.classify(
                LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState>(NativeConstants.EPERM, -1),
            ),
        )
    }
}
