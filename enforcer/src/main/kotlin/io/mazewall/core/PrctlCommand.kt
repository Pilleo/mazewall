package io.mazewall.core

import io.mazewall.ffi.NativeConstants

/**
 * A type-safe sealed class hierarchy representing Linux prctl options and their arguments,
 * preventing misaligned parameters or invalid command configurations at compile time.
 */
@Suppress("MagicNumber")
public sealed class PrctlCommand(public val option: Int) {
    public abstract val arg2: NativeArg
    public abstract val arg3: NativeArg
    public abstract val arg4: NativeArg
    public abstract val arg5: NativeArg

    public data class SetNoNewPrivs(val enabled: Boolean) : PrctlCommand(NativeConstants.PR_SET_NO_NEW_PRIVS) {
        override val arg2: NativeArg get() = NativeArg.LongArg(if (enabled) 1L else 0L)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data object GetNoNewPrivs : PrctlCommand(NativeConstants.PR_GET_NO_NEW_PRIVS) {
        override val arg2: NativeArg get() = NativeArg.LongArg(0L)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data object GetSeccomp : PrctlCommand(NativeConstants.PR_GET_SECCOMP) {
        override val arg2: NativeArg get() = NativeArg.LongArg(0L)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class SetSeccomp(
        val mode: Long,
        val filter: NativeArg = NativeArg.NullArg
    ) : PrctlCommand(NativeConstants.PR_SET_SECCOMP) {
        override val arg2: NativeArg get() = NativeArg.LongArg(mode)
        override val arg3: NativeArg get() = filter
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class SetName(val name: NativeArg.MemoryArg) : PrctlCommand(NativeConstants.PR_SET_NAME) {
        override val arg2: NativeArg get() = name
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class GetName(val buffer: NativeArg.MemoryArg) : PrctlCommand(NativeConstants.PR_GET_NAME) {
        override val arg2: NativeArg get() = buffer
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class SetPdeathsig(val signal: Long) : PrctlCommand(NativeConstants.PR_SET_PDEATHSIG) {
        override val arg2: NativeArg get() = NativeArg.LongArg(signal)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    /**
     * Highly privileged operation for process memory manipulation.
     * Mostly used in tests to verify seccomp blocking.
     */
    public data class SetMm(val mmOption: Long, val arg: NativeArg = NativeArg.LongArg(0L)) : PrctlCommand(NativeConstants.PR_SET_MM) {
        override val arg2: NativeArg get() = NativeArg.LongArg(mmOption)
        override val arg3: NativeArg get() = arg
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class CapAmbient(val capOption: Long, val capability: Long) : PrctlCommand(NativeConstants.PR_CAP_AMBIENT) {
        override val arg2: NativeArg get() = NativeArg.LongArg(capOption)
        override val arg3: NativeArg get() = NativeArg.LongArg(capability)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class SetPtracer(val tracerPid: Long) : PrctlCommand(NativeConstants.PR_SET_PTRACER) {
        override val arg2: NativeArg get() = NativeArg.LongArg(tracerPid)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    /**
     * An escape hatch for dynamic or unmapped testing/diagnostic options.
     * @deprecated Use specialized [PrctlCommand] variants instead.
     */
    @Deprecated("Use specialized PrctlCommand variants instead.")
    public data class Raw(
        val rawOption: Int,
        override val arg2: NativeArg = NativeArg.LongArg(0L),
        override val arg3: NativeArg = NativeArg.LongArg(0L),
        override val arg4: NativeArg = NativeArg.LongArg(0L),
        override val arg5: NativeArg = NativeArg.LongArg(0L)
    ) : PrctlCommand(rawOption)
}
