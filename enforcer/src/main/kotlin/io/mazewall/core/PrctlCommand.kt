package io.mazewall.core

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

    public data class SetNoNewPrivs(val enabled: Boolean) : PrctlCommand(38) {
        override val arg2: NativeArg get() = NativeArg.LongArg(if (enabled) 1L else 0L)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data object GetNoNewPrivs : PrctlCommand(39) {
        override val arg2: NativeArg get() = NativeArg.LongArg(0L)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data object GetSeccomp : PrctlCommand(21) {
        override val arg2: NativeArg get() = NativeArg.LongArg(0L)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class SetSeccomp(
        val mode: Long,
        val filter: NativeArg = NativeArg.NullArg
    ) : PrctlCommand(22) {
        override val arg2: NativeArg get() = NativeArg.LongArg(mode)
        override val arg3: NativeArg get() = filter
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    public data class SetPtracer(val tracerPid: Long) : PrctlCommand(0x59616d61) {
        override val arg2: NativeArg get() = NativeArg.LongArg(tracerPid)
        override val arg3: NativeArg get() = NativeArg.LongArg(0L)
        override val arg4: NativeArg get() = NativeArg.LongArg(0L)
        override val arg5: NativeArg get() = NativeArg.LongArg(0L)
    }

    /** An escape hatch for dynamic or unmapped testing/diagnostic options. */
    public data class Raw(
        val rawOption: Int,
        override val arg2: NativeArg = NativeArg.LongArg(0L),
        override val arg3: NativeArg = NativeArg.LongArg(0L),
        override val arg4: NativeArg = NativeArg.LongArg(0L),
        override val arg5: NativeArg = NativeArg.LongArg(0L)
    ) : PrctlCommand(rawOption)
}
