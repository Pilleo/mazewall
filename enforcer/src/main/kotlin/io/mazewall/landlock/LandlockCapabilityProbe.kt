package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.ffi.NativeConstants

/** Pure classification of the Landlock ABI probe; permission failure is never capability absence. */
internal object LandlockCapabilityProbe {
    sealed interface Outcome {
        data class Supported(
            val abi: Int,
        ) : Outcome

        data object Unsupported : Outcome

        data class Rejected(
            val errno: Int,
        ) : Outcome
    }

    fun classify(result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState>): Outcome =
        when (result) {
            is LinuxNative.SyscallResult.Success ->
                if (result.value >= 1L) Outcome.Supported(result.value.toInt()) else Outcome.Unsupported
            is LinuxNative.SyscallResult.Error ->
                if (result.errno == NativeConstants.ENOSYS || result.errno == NativeConstants.EOPNOTSUPP) {
                    Outcome.Unsupported
                } else {
                    Outcome.Rejected(result.errno)
                }
        }
}
