package io.mazewall.landlock

import io.mazewall.KernelCapability
import io.mazewall.LinuxNative
import io.mazewall.core.NativeArg
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
        when (val capability = classifyCapability(result)) {
            is KernelCapability.Available -> Outcome.Supported(capability.value)
            KernelCapability.Unsupported -> Outcome.Unsupported
            is KernelCapability.Rejected -> Outcome.Rejected(capability.errno)
            is KernelCapability.ProbeFailed -> throw capability.cause
        }

    fun classifyCapability(result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState>): KernelCapability<Int> =
        when (result) {
            is LinuxNative.SyscallResult.Success ->
                if (result.value >= 1L) KernelCapability.Available(result.value.toInt()) else KernelCapability.Unsupported
            is LinuxNative.SyscallResult.Error ->
                if (result.errno == NativeConstants.ENOSYS || result.errno == NativeConstants.EOPNOTSUPP) {
                    KernelCapability.Unsupported
                } else {
                    KernelCapability.Rejected(result.errno)
                }
        }

    fun probeCapability(): KernelCapability<Int> =
        try {
            classifyCapability(
                LinuxNative.raw.syscall(
                    NativeConstants.LANDLOCK_CREATE_RULESET_NR,
                    NativeArg.LongArg(0L),
                    NativeArg.LongArg(0L),
                    NativeArg.LongArg(NativeConstants.LANDLOCK_CREATE_RULESET_VERSION),
                ),
            )
        } catch (cause: UnsupportedOperationException) {
            KernelCapability.ProbeFailed(cause)
        } catch (cause: IllegalStateException) {
            KernelCapability.ProbeFailed(cause)
        } catch (cause: LinkageError) {
            KernelCapability.ProbeFailed(cause)
        }
}
