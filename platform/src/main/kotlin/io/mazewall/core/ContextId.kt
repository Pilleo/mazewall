package io.mazewall.core

/**
 * Semantic execution context attributed to Linux syscalls by the Tier E enrichment plane
 * (`docs/internals/designs/profiler/tier-e-design.md`).
 *
 * # UNTRUSTED ATTRIBUTION METADATA
 *
 * A [ContextId] is supplied by the observed process itself (via the Mazewall context marker).
 * A compromised tracee can forge arbitrary values at will. Context metadata MUST NOT be used
 * as an input to any enforcement decision; it feeds profiling, behavioral discovery,
 * observability and detection hints only. Enforcement remains the exclusive domain of
 * seccomp/Landlock and the USER_NOTIF supervisor machinery.
 *
 * Wire contract: exactly [WIRE_SIZE_BYTES] (4) bytes, big-endian, no varint, no nullable
 * representation. The value `0` is reserved as the fail-unknown sentinel ([UNKNOWN]): every
 * uncertain case — missing scope declaration, storage-create failure, dropped events,
 * pre-attach windows — must degrade to [UNKNOWN], never to a guessed neighbor value.
 */
@JvmInline
public value class ContextId(
    public val value: UInt,
) {
    /**
     * `true` when this instance is the [UNKNOWN] sentinel.
     */
    public val isUnknown: Boolean get() = value == UNKNOWN.value

    /**
     * Encodes this context id into its fixed 4-byte big-endian wire form.
     */
    public fun encode(): ByteArray {
        val dst = ByteArray(WIRE_SIZE_BYTES)
        encodeInto(dst)
        return dst
    }

    /**
     * Encodes this context id in big-endian order into [dst] starting at [offset].
     *
     * @throws IllegalArgumentException when [dst] has fewer than [WIRE_SIZE_BYTES] bytes
     *   available from [offset].
     */
    public fun encodeInto(dst: ByteArray, offset: Int = 0) {
        require(offset >= 0 && dst.size - offset >= WIRE_SIZE_BYTES) {
            "buffer too small for ${WIRE_SIZE_BYTES}-byte context id: size=${dst.size}, offset=$offset"
        }
        @Suppress("MagicNumber")
        val v = value.toInt()
        @Suppress("MagicNumber")
        dst[offset] = (v ushr 24).toByte()
        @Suppress("MagicNumber")
        dst[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        @Suppress("MagicNumber")
        dst[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        dst[offset + 3] = (v and 0xFF).toByte()
    }

    override fun toString(): String = "context($value)"

    public companion object {
        /**
         * Fail-unknown sentinel. Never attribute a guessed context.
         */
        public val UNKNOWN: ContextId = ContextId(0u)

        /**
         * Fixed wire size of a serialized [ContextId].
         */
        public const val WIRE_SIZE_BYTES: Int = 4

        /**
         * Decodes a [ContextId] from its fixed big-endian wire form. Decoding four zero bytes
         * yields [UNKNOWN]; that is a valid, expected outcome of lossy or unattributed paths.
         *
         * @throws IllegalArgumentException when fewer than [WIRE_SIZE_BYTES] bytes are
         *   available in [src] from [offset].
         */
        public fun decodeFrom(src: ByteArray, offset: Int = 0): ContextId {
            require(offset >= 0 && src.size - offset >= WIRE_SIZE_BYTES) {
                "buffer too small for ${WIRE_SIZE_BYTES}-byte context id: size=${src.size}, offset=$offset"
            }
            @Suppress("MagicNumber")
            val v = ((src[offset].toInt() and 0xFF) shl 24) or
                ((src[offset + 1].toInt() and 0xFF) shl 16) or
                ((src[offset + 2].toInt() and 0xFF) shl 8) or
                (src[offset + 3].toInt() and 0xFF)
            return ContextId(v.toUInt())
        }
    }
}
