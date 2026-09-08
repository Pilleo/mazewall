package io.mazewall.profiler.attribution

/** Session-local identifier for a canonical logical Java stack. */
@JvmInline
public value class StackTraceId(
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
public data class ManagedFrame(
    val classLoaderIdentity: String,
    val className: String,
    val methodName: String,
    val descriptor: String,
    val bytecodeLocation: Long,
    val kind: ManagedFrameKind,
)

public enum class ManagedFrameKind {
    JAVA,
    NATIVE_METHOD,
}

/** Distinguishes a complete captured sequence from a depth-limited one. */
public enum class StackCaptureQuality {
    COMPLETE,
    TRUNCATED,
}

/** Immutable dictionary record that may be delivered after syscall observations. */
public data class StackDefinition(
    val stackTraceId: StackTraceId,
    val frames: List<ManagedFrame>,
    val captureQuality: StackCaptureQuality,
)

/**
 * A process-local canonical stack dictionary.
 *
 * The map key is the entire immutable frame sequence and its capture quality. Hashing only
 * locates candidates; Kotlin equality compares every field before an existing ID is reused.
 */
public class StackTraceDictionary {
    private val definitionsByKey = mutableMapOf<DefinitionKey, StackDefinition>()
    private val definitionsById = mutableMapOf<StackTraceId, StackDefinition>()
    private var nextId: Long = 1

    public fun intern(
        frames: List<ManagedFrame>,
        captureQuality: StackCaptureQuality,
    ): StackDefinition {
        val key = DefinitionKey(frames.toList(), captureQuality)
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
