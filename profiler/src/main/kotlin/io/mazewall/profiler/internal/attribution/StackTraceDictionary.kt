package io.mazewall.profiler.internal.attribution

/** Session-local identifier for a canonical logical Java stack. */
@JvmInline
internal value class StackTraceId(
    public val value: Long,
) {
    init {
        require(value in 1..MAX_VALUE) { "stack trace id must be in 1..$MAX_VALUE" }
    }

    public companion object {
        public const val MAX_VALUE: Long = 0xFFFF_FFFFL
    }
}

/** The frame representation is JVM-neutral and safe to export after class unloading. */
internal data class ManagedFrame(
    val classLoaderIdentity: String,
    val className: String,
    val methodName: String,
    val descriptor: String,
    val bytecodeLocation: Long,
    val kind: ManagedFrameKind,
)

internal enum class ManagedFrameKind {
    JAVA,
    NATIVE_METHOD,
}

/** Distinguishes a complete captured sequence from a depth-limited one. */
internal enum class StackCaptureQuality {
    COMPLETE,
    TRUNCATED,
}

/** Immutable dictionary record that may be delivered after syscall observations. */
internal class StackDefinition(
    public val stackTraceId: StackTraceId,
    frames: List<ManagedFrame>,
    public val captureQuality: StackCaptureQuality,
) {
    public val frames: List<ManagedFrame> = java.util.List.copyOf(frames)

    override fun equals(other: Any?): Boolean =
        other is StackDefinition &&
            stackTraceId == other.stackTraceId &&
            frames == other.frames &&
            captureQuality == other.captureQuality

    override fun hashCode(): Int = listOf(stackTraceId, frames, captureQuality).hashCode()

    override fun toString(): String = "StackDefinition(stackTraceId=$stackTraceId, frames=$frames, captureQuality=$captureQuality)"
}

/**
 * A process-local canonical stack dictionary.
 *
 * The map key is the entire immutable frame sequence and its capture quality. Hashing only
 * locates candidates; Kotlin equality compares every field before an existing ID is reused.
 */
internal class StackTraceDictionary {
    private val definitionsByKey = mutableMapOf<DefinitionKey, StackDefinition>()
    private val definitionsById = mutableMapOf<StackTraceId, StackDefinition>()
    private var nextId: Long = 1

    public fun intern(
        frames: List<ManagedFrame>,
        captureQuality: StackCaptureQuality,
    ): StackDefinition {
        val key = DefinitionKey(java.util.List.copyOf(frames), captureQuality)
        return definitionsByKey[key] ?: allocate(key)
    }

    public fun resolve(stackTraceId: StackTraceId): StackDefinition? = definitionsById[stackTraceId]

    private fun allocate(key: DefinitionKey): StackDefinition {
        check(nextId <= StackTraceId.MAX_VALUE) { "stack trace id space exhausted" }
        val definition = StackDefinition(StackTraceId(nextId++), key.frames, key.captureQuality)
        definitionsByKey[key] = definition
        definitionsById[definition.stackTraceId] = definition
        return definition
    }

    private data class DefinitionKey(
        val frames: List<ManagedFrame>,
        val captureQuality: StackCaptureQuality,
    )
}
